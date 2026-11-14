package com.sondo.music.video.ai.kuikly.view

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.sondo.music.video.ai.kuikly.module.SDMediaKit
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback

/**
 * 内置网页视图（隐私政策 / 用户协议 / 活动页）。
 *
 * 属性：url
 * 事件：sdTitle {title}、sdLoad {state:"start"|"finish"|"error", progress(0..1)}（加载过程中以 start 状态持续上报进度）
 * 方法：reload / goBack（无法后退时不做任何事）
 *
 * JS 与 DOM Storage 开启；非 http(s) 协议的链接（tel: / mailto: / weixin: / alipays: / intent: …）交给系统处理；
 * 视图销毁时 WebView 一并销毁。
 */
class SDWebView(context: Context) : FrameLayout(context), IKuiklyRenderViewExport {

    private var url = ""
    private var titleCallback: KuiklyRenderCallback? = null
    private var loadCallback: KuiklyRenderCallback? = null

    private var webView: WebView? = null
    private var destroyed = false
    private var mainFrameFailed = false
    private var loadProgress = 0f
    private var lastLoadEvent = ""
    private var lastTitle = ""
    private var lifecycleOwner: LifecycleOwner? = null

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> webView?.onResume()
            Lifecycle.Event.ON_PAUSE -> webView?.onPause()
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit
        }
    }

    init {
        setWillNotDraw(false)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        when (propKey) {
            "url" -> {
                val value = (propValue as? String)?.trim().orEmpty()
                if (value != url) {
                    url = value
                    loadUrl()
                }
            }
            EVENT_TITLE -> {
                titleCallback = SDMediaKit.propCallback(propValue)
                webView?.title?.takeIf { it.isNotEmpty() }?.let { fireTitle(it) }
            }
            EVENT_LOAD -> loadCallback = SDMediaKit.propCallback(propValue)
            else -> return super.setProp(propKey, propValue)
        }
        return true
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            "reload" -> {
                val wv = webView
                if (wv != null && !wv.url.isNullOrEmpty()) wv.reload() else loadUrl()
            }
            "goBack" -> webView?.let { if (it.canGoBack()) it.goBack() }
            else -> return super.call(method, params, callback)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        destroy()
    }

    override fun draw(canvas: Canvas) {
        val checkpoint = if (hasCustomClipPath()) canvas.save() else -1
        drawCommonDecoration(width, height, canvas)
        super.draw(canvas)
        if (checkpoint != -1) canvas.restoreToCount(checkpoint)
        drawCommonForegroundDecoration(width, height, canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (destroyed || lifecycleOwner != null) return
        val owner = activity as? LifecycleOwner ?: return
        lifecycleOwner = owner
        try {
            owner.lifecycle.addObserver(lifecycleObserver)
        } catch (e: Throwable) {
            lifecycleOwner = null
        }
    }

    private fun destroy() {
        if (destroyed) return
        destroyed = true
        lifecycleOwner?.let {
            try {
                it.lifecycle.removeObserver(lifecycleObserver)
            } catch (e: Throwable) {
                // ignore
            }
        }
        lifecycleOwner = null
        titleCallback = null
        loadCallback = null
        val wv = webView ?: return
        webView = null
        try {
            wv.stopLoading()
            wv.webChromeClient = null
            wv.webViewClient = WebViewClient()
            wv.setDownloadListener(null)
            removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "destroy webview failed", e)
        }
    }

    private fun loadUrl() {
        if (destroyed) return
        if (url.isEmpty()) {
            webView?.loadUrl("about:blank")
            return
        }
        val wv = ensureWebView()
        if (wv == null) {
            fireLoad(STATE_ERROR, 0f)
            return
        }
        mainFrameFailed = false
        wv.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        val wv = try {
            // 用 Activity 作为 Context：页面里的 <select> / alert 等需要弹窗
            WebView(activity ?: context)
        } catch (e: Throwable) {
            // 系统 WebView 组件缺失 / 正在升级
            Log.w(TAG, "create webview failed", e)
            return null
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 只展示远程页面，不需要读本地文件
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        wv.overScrollMode = OVER_SCROLL_NEVER
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { downloadUrl, _, _, _, _ -> openExternally(Uri.parse(downloadUrl)) }
        addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView = wv
        return wv
    }

    /** 非 http(s) 链接交给系统；返回是否已处理。 */
    private fun handleExternalScheme(target: String): Boolean {
        val uri = try {
            Uri.parse(target)
        } catch (e: Throwable) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" ||
            scheme == "blob" || scheme == "javascript" || scheme == "file"
        ) {
            return false
        }
        if (scheme == "intent") {
            try {
                val intent = Intent.parseUri(target, Intent.URI_INTENT_SCHEME)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                startExternal(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "bad intent url", e)
            }
            return true
        }
        openExternally(uri)
        return true
    }

    private fun openExternally(uri: Uri) {
        startExternal(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun startExternal(intent: Intent) {
        try {
            val host = activity
            if (host != null) {
                host.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: ActivityNotFoundException) {
            // 没有能处理该协议的应用，忽略
        } catch (e: Throwable) {
            Log.w(TAG, "start external failed", e)
        }
    }

    private fun fireTitle(title: String) {
        if (destroyed || mainFrameFailed || title == lastTitle) return
        lastTitle = title
        try {
            titleCallback?.invoke(mapOf("title" to title))
        } catch (e: Throwable) {
            Log.w(TAG, "fire sdTitle failed", e)
        }
    }

    private fun fireLoad(state: String, progress: Float) {
        if (destroyed) return
        val value = Math.round(progress.coerceIn(0f, 1f) * 100f) / 100.0
        val signature = "$state|$value"
        if (signature == lastLoadEvent) return
        lastLoadEvent = signature
        try {
            loadCallback?.invoke(mapOf("state" to state, "progress" to value))
        } catch (e: Throwable) {
            Log.w(TAG, "fire sdLoad failed", e)
        }
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            return handleExternalScheme(request.url.toString())
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            return handleExternalScheme(url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
            lastTitle = ""
            // 进度回调可能先于 onPageStarted 到达，这里不把进度往回拨
            if (loadProgress >= 1f) loadProgress = 0f
            fireLoad(STATE_START, loadProgress)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (mainFrameFailed) return
            loadProgress = 1f
            fireLoad(STATE_FINISH, 1f)
            view.title?.takeIf { it.isNotEmpty() && !it.startsWith("http") && it != "about:blank" }?.let { fireTitle(it) }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // 只关心主文档失败，子资源（图片 / 统计脚本）失败不算页面错误
            if (!request.isForMainFrame) return
            mainFrameFailed = true
            loadProgress = 1f
            fireLoad(STATE_ERROR, 0f)
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (mainFrameFailed || newProgress >= 100) return
            loadProgress = newProgress / 100f
            fireLoad(STATE_START, loadProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            val t = title.orEmpty()
            // 加载初期 WebView 会把 URL 当标题回调一次，过滤掉
            if (t.isEmpty() || t.startsWith("http://") || t.startsWith("https://") || t == "about:blank") return
            fireTitle(t)
        }
    }

    companion object {
        const val VIEW_NAME = "SDWebView"
        private const val TAG = "SDWebView"
        private const val EVENT_TITLE = "sdTitle"
        private const val EVENT_LOAD = "sdLoad"
        private const val STATE_START = "start"
        private const val STATE_FINISH = "finish"
        private const val STATE_ERROR = "error"
    }
}
