package com.sondo.music.video.ai.kuikly

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sondo.music.video.ai.BuildConfig
import com.sondo.music.video.ai.R
import com.tencent.kuikly.core.render.android.css.ktx.toMap
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.module.KeyboardStatusListener
import com.tencent.kuikly.core.render.android.expand.module.KeyboardStatusWatcher
import org.json.JSONObject

/**
 * Kuikly 页面承载容器。一个 Activity 承载一个 Kuikly Pager，
 * 页面名通过 [KEY_PAGE_NAME] 传入，缺省为启动页 [DEFAULT_PAGE]。
 *
 * 除承载页面外，这里还负责：
 * - 向 [ActivityStack] 登记 / 注销（SDNavModule 的 popTo / stack 依赖它）；
 * - 把权限申请结果转给 [PermissionDispatcher]；
 * - 跟踪窗口 inset：系统导航栏模式、键盘高度（SDAppModule 的 getSystemNavMode 与 keyboard 事件）。
 */
open class KuiklyRenderActivity : AppCompatActivity() {

    private var containerView: ViewGroup? = null
    private var delegator: KuiklyRenderViewBaseDelegator? = null
    private var pageDelegate: KuiklyPageDelegate? = null

    val pageName: String
        get() = intent?.getStringExtra(KEY_PAGE_NAME)?.takeIf { it.isNotEmpty() } ?: DEFAULT_PAGE

    /** 在 [ActivityStack] 里的排序序号（越大越靠近栈顶）。 */
    var stackSeq: Long = 0L
        private set

    /** 已被要求关闭（finish 已调用或即将调用）；[ActivityStack] 的查询会跳过它。 */
    @Volatile
    var leaving: Boolean = false
        private set

    /** 是否允许返回键 / 返回手势关闭当前页（SDNavModule.setSwipeBackEnabled）。 */
    @Volatile
    var backEnabled: Boolean = true

    // ---- 窗口 inset 快照：主线程写，Kuikly 线程读（getSystemNavMode 是同步方法） ----

    /** 是否三键（含两键）导航。 */
    @Volatile
    var threeButtonNav: Boolean = false
        private set

    /** 系统导航栏占用的底部高度（dp）。 */
    @Volatile
    var navBottomInsetDp: Double = 0.0
        private set

    private var navBottomInsetPx = 0
    private var insetsReady = false
    private var keyboardHeightPx = -1
    private var keyboardWatching = false
    private var legacyKeyboardWatcher: KeyboardStatusWatcher? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** 启动页在 Kuikly 首帧出来之前要垫的背景（闪屏图）；普通页面返回 0。 */
    protected open val splashBackgroundRes: Int get() = 0

    /** 是否透明页（盖在上一页之上，不铺底色）。 */
    protected open val isTranslucentPage: Boolean get() = false

    /** 子类可在 super.onCreate 之后、挂载 Kuikly 页面之前中止创建（返回 true 时由子类负责 finish）。 */
    protected open fun shouldAbortCreate(savedInstanceState: Bundle?): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPageTransition(true)
        super.onCreate(savedInstanceState)
        if (shouldAbortCreate(savedInstanceState)) return
        if (restartIfRootMissing(savedInstanceState)) return

        stackSeq = ActivityStack.acquireSeq(savedInstanceState?.getLong(STATE_STACK_SEQ, 0L) ?: 0L)
        KuiklyAdapters.install(application)
        val page = KuiklyPageDelegate(this, pageName) { onKuiklyPageLoaded() }
        pageDelegate = page
        val renderDelegator = page.createDelegator()
        delegator = renderDelegator

        setContentView(R.layout.activity_kuikly)
        setupImmersiveMode()
        val container = findViewById<ViewGroup>(R.id.kuikly_container)
        containerView = container
        seedSystemNavInfo()
        observeWindowInsets(container)
        applyKeepScreenOn(ActivityStack.keepScreenOn)

        ActivityStack.register(this)
        renderDelegator.onAttach(container, "", pageName, createPageData())
    }

    /**
     * 进程被回收后系统只重建栈顶页面，根页面 launch（AppHost / AppBootstrap / 隐私 Gate 都挂在它上面）不会跟着重建，
     * 这时业务页拿不到应用级服务。没有存活的根页面时不恢复本页：清空任务，从桌面入口重新启动。
     */
    private fun restartIfRootMissing(savedInstanceState: Bundle?): Boolean {
        if (savedInstanceState == null || pageName == DEFAULT_PAGE) return false
        if (ActivityStack.live().any { it.pageName == DEFAULT_PAGE }) return false
        val component = packageManager.getLaunchIntentForPackage(packageName)?.component
            ?: android.content.ComponentName(this, KuiklyLaunchActivity::class.java)
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        leaving = true
        startActivity(launch)
        finishWithoutTransition()
        overridePendingTransition(0, 0)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_STACK_SEQ, stackSeq)
    }

    override fun onResume() {
        super.onResume()
        delegator?.onResume()
    }

    override fun onPause() {
        super.onPause()
        delegator?.onPause()
    }

    override fun onDestroy() {
        leaving = true
        ActivityStack.unregister(this)
        PermissionDispatcher.cancel(this)
        releaseInsetObservers()
        super.onDestroy()
        delegator?.onDetach()
    }

    override fun finish() {
        leaving = true
        super.finish()
        applyPageTransition(false)
    }

    /** 结束页面但不套用本页的退场动画（被盖住的中间页、replace / resetRoot 里的旧页）。 */
    fun finishWithoutTransition() {
        leaving = true
        super.finish()
    }

    /** 仅标记，稍后在主线程真正 finish。 */
    fun markLeaving() {
        leaving = true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { pageDelegate?.onNewIntent(it) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        delegator?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionDispatcher.dispatch(this, requestCode, permissions, grantResults)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            // 先给 Kuikly 页面处理（BackPressModule）；页面没消费且禁用了返回时，吞掉按键
            if (delegator?.onBackPressed() == true) return true
            if (!backEnabled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ 供原生模块调用

    /** "light"：浅色图标（深色背景用）；"dark"：深色图标。 */
    fun setStatusBarStyle(style: String) {
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = style == "dark"
    }

    fun applyKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** 排除系统手势区域（px，相对页面根视图）。Android 10 以下没有手势导航，无需处理。 */
    fun setGestureExclusionRects(rects: List<Rect>) {
        val container = containerView ?: return
        if (Build.VERSION.SDK_INT >= 29) {
            ViewCompat.setSystemGestureExclusionRects(container, rects)
        }
    }

    /**
     * 开始监听键盘高度（页面注册了 SDAppModule 事件监听后调用，可重复调用）。
     *
     * 容器是 adjustNothing（Kuikly 要求），窗口不会因键盘改变大小：
     * - Android 11+：直接读 WindowInsets 里的 IME inset；
     * - 更低版本：IME inset 不会分发给 adjustNothing 的窗口，复用 Kuikly 渲染器自带的 PopupWindow 探测方案。
     */
    fun startKeyboardWatching() {
        if (keyboardWatching || isFinishing || isDestroyed) return
        keyboardWatching = true
        if (Build.VERSION.SDK_INT >= 30) {
            // 之后的变化由 inset 监听驱动；这里先同步一次当前状态（窗口还没 attach 时拿不到，等首次布局）
            containerView?.let { ViewCompat.getRootWindowInsets(it) }?.let { onWindowInsets(it) }
        } else {
            legacyKeyboardWatcher = KeyboardStatusWatcher(this).also { watcher ->
                watcher.addListener(object : KeyboardStatusListener {
                    override fun onHeightChanged(height: Int) {
                        // 探测值是「键盘顶到导航栏顶」的距离，补上导航栏高度后才是距窗口底部的高度
                        onKeyboardHeight(if (height > 0) height + navBottomInsetPx else 0)
                    }
                })
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    protected open fun onKuiklyPageLoaded() = Unit

    private fun createPageData(): Map<String, Any> {
        val param: MutableMap<String, Any> = try {
            intent?.getStringExtra(KEY_PAGE_DATA)?.takeIf { it.isNotEmpty() }?.let { JSONObject(it).toMap() }
        } catch (e: Throwable) {
            null
        } ?: mutableMapOf()
        param["debug"] = if (BuildConfig.DEBUG) 1 else 0
        param["apiEnv"] = BuildConfig.API_ENV
        param["showTokenEditor"] = if (BuildConfig.SHOW_TOKEN_EDITOR) 1 else 0
        param["appVersionName"] = BuildConfig.VERSION_NAME
        param["appVersionCode"] = BuildConfig.VERSION_CODE
        return param
    }

    /** 入场 / 退场动画，对应 Flutter 侧 GetPage.transition（downToUp / fadeIn / noTransition）。 */
    private fun applyPageTransition(enter: Boolean) {
        when (intent?.getStringExtra(KEY_TRANSITION)) {
            TRANSITION_NONE -> overridePendingTransition(0, 0)
            TRANSITION_FADE -> overridePendingTransition(
                if (enter) android.R.anim.fade_in else 0,
                if (enter) 0 else android.R.anim.fade_out
            )
            TRANSITION_BOTTOM -> overridePendingTransition(
                if (enter) R.anim.slide_in_bottom else R.anim.stay,
                if (enter) R.anim.stay else R.anim.slide_out_bottom
            )
            else -> Unit
        }
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // 全站深色主题：状态栏 / 导航栏图标固定为浅色
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = findViewById<View>(R.id.kuikly_root)
        val splash = splashBackgroundRes
        when {
            // 透明页：窗口背景由 AppTheme.Translucent 给出（透明），内容层不能再铺底色
            isTranslucentPage -> root?.background = null
            // 启动页：闪屏图垫在最底层，内容层保持透明，Kuikly 首帧画出来之前用户一直看到闪屏
            splash != 0 -> {
                window.setBackgroundDrawableResource(splash)
                root?.background = null
            }
            else -> {
                window.decorView.setBackgroundColor(Color.BLACK)
                findViewById<View>(android.R.id.content)?.setBackgroundColor(Color.BLACK)
            }
        }
    }

    /** Kuikly 首帧完成后撤掉闪屏背景（释放位图，也避免透明区域露出闪屏）。 */
    protected fun clearSplashBackground() {
        if (isDestroyed) return
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        findViewById<View>(R.id.kuikly_root)?.setBackgroundColor(Color.BLACK)
    }

    /**
     * 窗口还没 attach 时拿不到 inset（Kuikly 页面在 onCreate 里就开始执行），先给一个估计值：
     * 优先沿用栈顶页面已经测到的值，否则读系统资源里的导航模式与导航栏高度。真实 inset 到达后以真实值为准。
     */
    private fun seedSystemNavInfo() {
        val prev = ActivityStack.top()
        if (prev != null && prev.insetsReady) {
            threeButtonNav = prev.threeButtonNav
            navBottomInsetPx = prev.navBottomInsetPx
            navBottomInsetDp = prev.navBottomInsetDp
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            // tappableElement 从 API 30 才有；Android 8/9 只有按键导航，Android 10 无法区分，一律按三键
            threeButtonNav = true
        } else {
            // config_navBarInteractionMode：0 三键，1 两键，2 手势
            val modeId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            threeButtonNav = modeId != 0 && runCatching { resources.getInteger(modeId) }.getOrDefault(2) != 2
        }
        val heightId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        navBottomInsetPx = if (heightId != 0) runCatching { resources.getDimensionPixelSize(heightId) }.getOrDefault(0) else 0
        navBottomInsetDp = pxToDp(navBottomInsetPx)
    }

    private fun observeWindowInsets(container: ViewGroup) {
        // 一律读「根 inset」：分发到子 View 的 inset 可能已被上层（AppCompat 的 subDecor 等）消费或改写
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            onWindowInsets(ViewCompat.getRootWindowInsets(view) ?: insets)
            insets
        }
        // inset 在上层被消费时子 View 收不到分发；窗口 inset 变化必然伴随一次布局（ViewRootImpl 会 requestLayout），
        // 所以布局回调里再读一次兜底。adjustNothing 下键盘弹起也是走这条路（做法同 Kuikly 自带的键盘监听）
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            ViewCompat.getRootWindowInsets(container)?.let { onWindowInsets(it) }
        }
        globalLayoutListener = listener
        container.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun onWindowInsets(insets: WindowInsetsCompat) {
        val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        // 三键（含两键）底栏有可点击按钮；手势导航这块高度为 0。tappableElement 从 API 30 才有，更早一律按三键
        val threeButton = if (Build.VERSION.SDK_INT < 30) {
            true
        } else {
            insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom > 0
        }
        val changed = !insetsReady || navBottom != navBottomInsetPx || threeButton != threeButtonNav
        insetsReady = true
        navBottomInsetPx = navBottom
        navBottomInsetDp = pxToDp(navBottom)
        threeButtonNav = threeButton
        if (changed) {
            // 契约之外的补充事件：系统设置里切换手势 / 三键、或首帧 inset 就绪时通知页面重读
            AppEventHub.emitToPage(
                this,
                JSONObject().put("event", "systemNav")
                    .put("threeButton", threeButton)
                    .put("bottomInset", navBottomInsetDp)
            )
        }
        if (keyboardWatching && Build.VERSION.SDK_INT >= 30) {
            val ime = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else {
                0
            }
            onKeyboardHeight(ime)
        }
    }

    private fun onKeyboardHeight(heightPx: Int) {
        val height = heightPx.coerceAtLeast(0)
        if (height == keyboardHeightPx) return
        // 第一次读到 0 只是初始状态，不算变化
        val first = keyboardHeightPx < 0
        keyboardHeightPx = height
        if (first && height == 0) return
        AppEventHub.emitToPage(
            this,
            JSONObject().put("event", "keyboard")
                .put("height", pxToDp(height))
                .put("bottomInset", navBottomInsetDp)
        )
    }

    private fun releaseInsetObservers() {
        globalLayoutListener?.let { containerView?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        globalLayoutListener = null
        try {
            legacyKeyboardWatcher?.destroy()
        } catch (e: Throwable) {
            // PopupWindow 在窗口已移除时 dismiss 可能抛异常，忽略
        }
        legacyKeyboardWatcher = null
    }

    private fun pxToDp(px: Int): Double {
        val density = resources.displayMetrics.density
        if (density <= 0f) return px.toDouble()
        return Math.round(px / density * 100.0) / 100.0
    }

    companion object {
        const val DEFAULT_PAGE = "launch"
        const val KEY_PAGE_NAME = "pageName"
        const val KEY_PAGE_DATA = "pageData"
        const val KEY_TRANSITION = "sdTransition"
        const val TRANSITION_NONE = "none"
        const val TRANSITION_FADE = "fade"
        const val TRANSITION_BOTTOM = "bottom"
        private const val STATE_STACK_SEQ = "sd_stack_seq"

        fun start(context: Context, pageName: String, pageData: JSONObject) {
            val starter = Intent(context, activityClassFor(pageData))
            starter.putExtra(KEY_PAGE_NAME, pageName)
            starter.putExtra(KEY_PAGE_DATA, pageData.toString())
            pageData.optString(KEY_TRANSITION).takeIf { it.isNotEmpty() }?.let {
                starter.putExtra(KEY_TRANSITION, it)
            }
            if (context !is android.app.Activity) {
                starter.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(starter)
        }

        private fun activityClassFor(pageData: JSONObject): Class<*> {
            return if (pageData.optInt("sdTranslucent", 0) == 1) {
                KuiklyTranslucentActivity::class.java
            } else {
                KuiklyRenderActivity::class.java
            }
        }
    }
}

/** 透明背景容器：创作菜单等「盖在上一页之上」的页面使用。 */
class KuiklyTranslucentActivity : KuiklyRenderActivity() {
    override val isTranslucentPage: Boolean get() = true
}

/**
 * 桌面图标入口：承载根页面（launch）。
 *
 * 与普通页面容器的区别只有启动体验：清单里用 LaunchTheme（闪屏图 / Android 12+ 系统闪屏），
 * 进入 onCreate 后切回 AppTheme，并把闪屏图垫在窗口背景上，直到 Kuikly 首帧完成（对应 Flutter 的 LaunchTheme → NormalTheme）。
 */
class KuiklyLaunchActivity : KuiklyRenderActivity() {

    override val splashBackgroundRes: Int get() = R.drawable.launch_background

    override fun onCreate(savedInstanceState: Bundle?) {
        // LaunchTheme 不是 AppCompat 主题，必须在 super.onCreate 之前换回来
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }

    /**
     * 部分桌面 / 应用商店的「打开」会在已有任务之上再拉一次 LAUNCHER Activity。
     * 页面栈还在时直接结束这个多余的实例，露出原来的栈顶页面。
     */
    override fun shouldAbortCreate(savedInstanceState: Bundle?): Boolean {
        val launchedFromLauncher = intent?.action == Intent.ACTION_MAIN &&
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        if (savedInstanceState == null && launchedFromLauncher && !ActivityStack.isEmpty()) {
            finishWithoutTransition()
            overridePendingTransition(0, 0)
            return true
        }
        return false
    }

    override fun onKuiklyPageLoaded() {
        clearSplashBackground()
    }
}
