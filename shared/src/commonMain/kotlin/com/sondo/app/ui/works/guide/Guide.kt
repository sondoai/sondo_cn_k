package com.sondo.app.ui.works.guide

import com.sondo.app.base.BasePager
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.tencent.kuikly.core.base.AbstractBaseView
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer

/** 引导气泡相对目标的方向（Dart guide_v3 `GuideTipDirection`）。 */
enum class GuideTipDirection { top, bottom, left, right }

/** 额外偏移（Dart `EdgeInsets margin`，v3 实际不使用，保留同名字段）。 */
data class GuideInsets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

/**
 * 单个引导步骤（Dart guide_v3 `GuideStep`）。
 *
 * Dart 的 `GlobalKey targetKey` → [target]：返回要高亮的视图（通常在目标的 `ref { }` 里存下 `ViewRef`，这里返回 `ref.view`）。
 * 浮层每次重绘时用 `convertFrame(frame, null)` 读取它在页面中的位置；返回 null 或尚未布局时浮层什么也不画（同 Dart）。
 * @param customContentBuilder 自定义内容（替代默认卡片 + 箭头），参数 onClose 等同 Skip
 */
class GuideStep(
    val id: String,
    val target: () -> AbstractBaseView<*, *>?,
    val title: String,
    val description: String,
    val direction: GuideTipDirection = GuideTipDirection.bottom,
    val margin: GuideInsets = GuideInsets(),
    val focusPadding: Float = 8f,
    val focusRadius: Float = 14f,
    val allowTapOverlayNext: Boolean = true,
    val customContentBuilder: (ViewContainer<*, *>.(onClose: () -> Unit) -> Unit)? = null,
) {
    fun copyWith(
        id: String? = null,
        target: (() -> AbstractBaseView<*, *>?)? = null,
        title: String? = null,
        description: String? = null,
        direction: GuideTipDirection? = null,
        margin: GuideInsets? = null,
        focusPadding: Float? = null,
        focusRadius: Float? = null,
        allowTapOverlayNext: Boolean? = null,
        customContentBuilder: (ViewContainer<*, *>.(onClose: () -> Unit) -> Unit)? = null,
    ): GuideStep = GuideStep(
        id ?: this.id,
        target ?: this.target,
        title ?: this.title,
        description ?: this.description,
        direction ?: this.direction,
        margin ?: this.margin,
        focusPadding ?: this.focusPadding,
        focusRadius ?: this.focusRadius,
        allowTapOverlayNext ?: this.allowTapOverlayNext,
        customContentBuilder ?: this.customContentBuilder,
    )
}

/** 步骤控制器（Dart `GuideStepController`）。 */
class GuideStepController(val steps: List<GuideStep>) {
    init {
        require(steps.isNotEmpty()) { "steps can not be empty" }
    }

    var currentIndex: Int = 0
        private set
    var isShowing: Boolean = false
        private set

    private var onFinish: (() -> Unit)? = null
    private var onClose: (() -> Unit)? = null
    private var onStepChanged: ((Int) -> Unit)? = null
    private val listeners = ArrayList<() -> Unit>()

    val total: Int get() = steps.size
    val isFirstStep: Boolean get() = currentIndex == 0
    val isLastStep: Boolean get() = currentIndex == steps.size - 1
    val currentStep: GuideStep get() = steps[currentIndex]

    /** 监听状态变化（浮层用）；返回注销函数。 */
    fun addListener(l: () -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    fun bindCallbacks(onFinish: (() -> Unit)? = null, onClose: (() -> Unit)? = null, onStepChanged: ((Int) -> Unit)? = null) {
        this.onFinish = onFinish
        this.onClose = onClose
        this.onStepChanged = onStepChanged
    }

    fun show(initialIndex: Int = 0) {
        currentIndex = initialIndex.coerceIn(0, steps.size - 1)
        isShowing = true
        notifyListeners()
        onStepChanged?.invoke(currentIndex)
    }

    /** 下一步；最后一步时 finish。 */
    fun next() {
        if (!isShowing) return
        if (currentIndex < steps.size - 1) {
            currentIndex++
            notifyListeners()
            onStepChanged?.invoke(currentIndex)
        } else {
            finish()
        }
    }

    fun prev() {
        if (!isShowing) return
        if (currentIndex > 0) {
            currentIndex--
            notifyListeners()
            onStepChanged?.invoke(currentIndex)
        }
    }

    fun jumpTo(index: Int) {
        if (!isShowing) return
        if (index < 0 || index >= steps.size) return
        currentIndex = index
        notifyListeners()
        onStepChanged?.invoke(currentIndex)
    }

    fun finish() {
        isShowing = false
        notifyListeners()
        onFinish?.invoke()
    }

    fun close() {
        isShowing = false
        notifyListeners()
        onClose?.invoke()
    }

    fun reset() {
        currentIndex = 0
        isShowing = false
        notifyListeners()
    }
}

/**
 * 引导层裁剪手柄配置（Dart `GuideCropHandlesConfig`）：在遮罩上方、目标底部 [trackHeight] 高的条带里放可拖动的左右手柄。
 * 回调参数为横向像素增量。
 */
class GuideCropHandlesConfig(
    val getStartPercent: () -> Float,
    val getEndPercent: () -> Float,
    val onStartDragUpdate: (Float) -> Unit,
    val onEndDragUpdate: (Float) -> Unit,
    val onSelectionDragUpdate: ((Float) -> Unit)? = null,
    val onDragEnd: (() -> Unit)? = null,
    val handleWidth: Float = 14f,
    val trackHeight: Float = 45f,
    val handleColor: Color = Color.TRANSPARENT,
    val handleGripColor: Color = Color.TRANSPARENT,
    val selectionBorderColor: Color = Color.TRANSPARENT,
)

/**
 * 引导浮层入口（Dart guide_v3 `GuideManager`）：同一时刻只有一个引导。
 * 「已展示」标记不在这里存——与 Dart 一样由调用方在 onFinish / onClose 里写 `Sp.setBool(StorageKey.xxxGuideShown, true)`。
 *
 * ```
 * val c = GuideStepController(listOf(GuideStep("playback_edit", { ctx.editRef.view }, "", "mv_playback_edit_guide_title".tr,
 *     allowTapOverlayNext = false)))
 * GuideManager.show(ctx, c, onFinish = { … }, onClose = { … })
 * ```
 */
object GuideManager {
    private var handle: DialogHandle? = null
    private var controller: GuideStepController? = null
    internal var cropHandles: GuideCropHandlesConfig? = null
        private set
    /** 浮层注册的重绘回调。 */
    internal var repaintHook: (() -> Unit)? = null

    val isShowing: Boolean get() = handle?.isShowing == true

    fun show(
        pager: BasePager,
        controller: GuideStepController,
        onFinish: (() -> Unit)? = null,
        onClose: (() -> Unit)? = null,
        onStepChanged: ((Int) -> Unit)? = null,
        initialIndex: Int = 0,
        cropHandles: GuideCropHandlesConfig? = null,
    ) {
        hide()
        this.controller = controller
        this.cropHandles = cropHandles
        controller.bindCallbacks(
            onFinish = {
                hide()
                onFinish?.invoke()
            },
            onClose = {
                hide()
                onClose?.invoke()
            },
            onStepChanged = onStepChanged,
        )
        lateinit var h: DialogHandle
        h = pager.showDialog(
            align = DialogEntry.ALIGN_FILL,
            dismissOnMask = false,
            dismissOnBack = true,
            maskColor = Color.TRANSPARENT,
            // 返回键关掉浮层 = 跳过（Dart 由页面 pop 时调 close）
            onDismiss = { if (handle === h && controller.isShowing) controller.close() },
        ) { _ ->
            GuideOverlay {
                attr { this.controller = controller }
            }
        }
        handle = h
        controller.show(initialIndex)
    }

    fun next() {
        controller?.next()
    }

    fun prev() {
        controller?.prev()
    }

    /** 关闭并触发 onClose。 */
    fun close() {
        controller?.close()
    }

    /** 请求重绘（裁剪区间或目标位置变化后调用）。 */
    fun repaint() {
        repaintHook?.invoke()
    }

    /** 静默移除，不触发回调。 */
    fun hide() {
        val h = handle
        handle = null
        controller = null
        cropHandles = null
        repaintHook = null
        h?.dismiss()
    }
}
