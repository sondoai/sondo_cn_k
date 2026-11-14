package com.sondo.app.base

import com.sondo.app.core.constants.UIColor
import com.sondo.app.nativeapi.NativeModules
import com.sondo.app.nativeapi.SDNavModule
import com.sondo.app.service.analytics.UmengUtils
import com.sondo.app.service.pay.PayService
import com.sondo.app.service.PushService
import com.sondo.app.ui.dialog.showGenerationCompleted
import com.sondo.app.ui.overlay.DialogEntry
import com.sondo.app.ui.overlay.DialogHandle
import com.sondo.app.ui.overlay.OverlayHost
import com.sondo.app.ui.overlay.OverlayState
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.coroutines.CoroutineScope
import com.tencent.kuikly.core.coroutines.Job
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.timer.setTimeout

/**
 * 所有业务页面的基类。
 *
 * 子类实现 [content] 返回页面 UI；`body()` 由基类接管，在内容之上叠加全局浮层
 * （Toast / Loading / 弹窗），因此任何页面都可以直接调用 [toast] / [showLoading] / [showDialog]。
 */
abstract class BasePager : Pager() {

    val nav: Nav by lazy(LazyThreadSafetyMode.NONE) { Nav(this) }

    /** 全局浮层状态（Toast / Loading / 弹窗栈）。 */
    val overlay: OverlayState by lazy(LazyThreadSafetyMode.NONE) { OverlayState(this) }

    /** 页面是否在前台可见。 */
    var isPageVisible: Boolean = false
        private set

    private val notifyRefs = ArrayList<Pair<String, CallbackRef>>()
    private val pendingResults = HashMap<String, (JSONObject?) -> Unit>()
    private var resultDelivered = false
    private var backCallback: BackPressCallback? = null

    // ------------------------------------------------------------------ 子类实现

    /** 页面内容。 */
    abstract fun content(): ViewBuilder

    /** 页面底色。 */
    open fun pageBackgroundColor(): Color = UIColor.pageBg

    /**
     * 系统返回键 / 侧滑返回。返回 true 表示已自行处理（不关闭页面）。
     * 仅在 [interceptBack] 返回 true 或有弹窗显示时才会被调用。
     */
    open fun onBackPressed(): Boolean = false

    /** 是否拦截系统返回（如编辑页未保存提示）。状态变化后调用 [refreshBackIntercept]。 */
    open fun interceptBack(): Boolean = false

    // ------------------------------------------------------------------ 生命周期

    override fun createExternalModules(): Map<String, Module>? = NativeModules.create()

    override fun created() {
        super.created()
        AppEnv.initFrom(pagerData)
        AppHooks.install()
        on(Events.PAGE_RESULT) { data ->
            val reqId = data.optString("reqId", "")
            val cb = pendingResults.remove(reqId)
            cb?.invoke(data.optJSONObject("result"))
        }
        refreshBackIntercept()
        // 前台推送：只由当前可见页面展示应用内横幅，点击按推送内容跳转
        on(Events.PUSH_FOREGROUND) { data ->
            if (!isPageVisible) return@on
            val extras = data.optJSONObject("extras") ?: JSONObject()
            showGenerationCompleted(
                title = data.optString("title", ""),
                subtitle = data.optString("body", ""),
            ) { PushService.handleForegroundTap(extras) }
        }
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        isPageVisible = true
        VisiblePage.appeared(this)
        // 页面统计（替代 GetX routingCallback）
        UmengUtils.onPagerAppear(this)
        // 让支付流程能把提示 / 关页落到当前可见页面
        PayService.attachPage(this)
    }

    override fun pageDidDisappear() {
        super.pageDidDisappear()
        isPageVisible = false
    }

    override fun pageWillDestroy() {
        VisiblePage.destroyed(this)
        // 页面带着弹窗被关掉（popToRoot / 深链回根）时也要跑弹窗的 onDismiss 清理
        try {
            overlay.dismissAllDialogs()
        } catch (e: Throwable) {
            // 清理失败不影响页面销毁
        }
        UmengUtils.onPagerDestroy(this)
        // 未带结果就关闭（侧滑 / 返回键）：通知打开方「无结果」
        deliverPageResult(null)
        val notify = notifyModule()
        notifyRefs.forEach { (name, ref) -> notify.removeNotify(name, ref) }
        notifyRefs.clear()
        pendingResults.clear()
        super.pageWillDestroy()
    }

    final override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                backgroundColor(ctx.pageBackgroundColor())
            }
            ctx.content().invoke(this)
            OverlayHost {
                attr { state = ctx.overlay }
            }
        }
    }

    // ------------------------------------------------------------------ 事件

    private fun notifyModule(): NotifyModule = acquireModule(NotifyModule.MODULE_NAME)

    private fun navModule(): SDNavModule = acquireModule(SDNavModule.MODULE_NAME)

    /** 订阅跨页面事件，页面销毁时自动注销。 */
    fun on(event: String, handler: (JSONObject) -> Unit) {
        val ref = notifyModule().addNotify(event) { handler(it ?: JSONObject()) }
        notifyRefs.add(event to ref)
    }

    /** 广播跨页面事件（本页面也会收到）。 */
    fun emit(event: String, data: JSONObject = JSONObject()) {
        notifyModule().postNotify(event, data)
    }

    // ------------------------------------------------------------------ 协程 / 定时

    /** 在本页面生命周期内启动协程（页面销毁后回调不再到达）。 */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = lifecycleScope.launch(block = block)

    /** 延时执行（毫秒）。返回值可传给 clearTimeout。 */
    fun postDelayed(delayMs: Int, block: () -> Unit): String = setTimeout(pagerId, delayMs, block)

    // ------------------------------------------------------------------ 页面结果

    internal fun awaitPageResult(reqId: String, cb: (JSONObject?) -> Unit) {
        pendingResults[reqId] = cb
    }

    internal fun deliverPageResult(result: JSONObject?) {
        if (resultDelivered) return
        val reqId = pagerData.params.optString(Nav.KEY_REQ_ID, "")
        if (reqId.isEmpty()) return
        resultDelivered = true
        val data = JSONObject().put("reqId", reqId)
        if (result != null) data.put("result", result)
        emit(Events.PAGE_RESULT, data)
    }

    // ------------------------------------------------------------------ 返回键

    /** 根据 [interceptBack]、弹窗栈与 Loading 刷新返回键拦截。 */
    fun refreshBackIntercept() {
        val need = interceptBack() || overlay.hasDialog() || overlay.isLoadingShowing()
        val handler = getBackPressHandler()
        val existing = backCallback
        if (need && existing == null) {
            val cb = object : BackPressCallback() {
                override fun handleOnBackPressed() {
                    if (overlay.dismissTopDialogByBack()) return
                    if (!onBackPressed()) nav.pop()
                }
            }
            backCallback = cb
            handler.addCallback(cb)
            // iOS 的系统侧滑返回不经过 Kuikly：需要拦截时关掉，改由返回事件走上面的回调
            navModule().setSwipeBackEnabled(false)
        } else if (!need && existing != null) {
            handler.removeCallback(existing)
            backCallback = null
            navModule().setSwipeBackEnabled(true)
        }
    }

    // ------------------------------------------------------------------ 浮层快捷方法

    /** 居中轻提示。 */
    fun toast(msg: String, durationMs: Int = 2000) = overlay.showToast(msg, durationMs)

    /** 顶部错误提示（接口错误同款样式）。 */
    fun toastError(msg: String) = overlay.showTopError(msg)

    fun showLoading(text: String = "") = overlay.showLoading(text)

    fun hideLoading() = overlay.hideLoading()

    /**
     * 展示弹窗 / 底部弹层。
     * @param builder 弹窗内容；通过 handle.dismiss() 关闭
     */
    fun showDialog(
        align: Int = DialogEntry.ALIGN_CENTER,
        dismissOnMask: Boolean = true,
        dismissOnBack: Boolean = true,
        maskColor: Color = Color(0, 0, 0, 0.6f),
        onDismiss: (() -> Unit)? = null,
        builder: ViewContainer<*, *>.(DialogHandle) -> Unit,
    ): DialogHandle = overlay.showDialog(align, dismissOnMask, dismissOnBack, maskColor, onDismiss, builder)

    fun showBottomSheet(
        dismissOnMask: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        builder: ViewContainer<*, *>.(DialogHandle) -> Unit,
    ): DialogHandle = showDialog(DialogEntry.ALIGN_BOTTOM, dismissOnMask, true, Color(0, 0, 0, 0.6f), onDismiss, builder)

    // ------------------------------------------------------------------ 尺寸

    val screenWidth: Float get() = pagerData.pageViewWidth
    val screenHeight: Float get() = pagerData.pageViewHeight
    val statusBarHeight: Float get() = pagerData.statusBarHeight
    /** 底部安全区（手势条 / Home Indicator）。 */
    val safeBottom: Float get() = pagerData.safeAreaInsets.bottom
}
