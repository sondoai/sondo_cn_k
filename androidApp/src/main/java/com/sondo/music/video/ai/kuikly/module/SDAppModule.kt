package com.sondo.music.video.ai.kuikly.module

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.sondo.music.video.ai.BuildConfig
import com.sondo.music.video.ai.kuikly.ActivityStack
import com.sondo.music.video.ai.kuikly.AppEventHub
import com.sondo.music.video.ai.kuikly.InstallChannel
import com.tencent.kuikly.core.render.android.IKuiklyRenderLifecycleCallback
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用 / 设备 / 系统 UI（契约：shared/nativeapi/SDAppModule.kt）。
 *
 * 同步方法（get… / now / canOpenUrl）运行在 Kuikly 线程，只读内存快照或做一次轻量的系统查询；
 * 其余方法到达时在主线程，涉及文件的工作（分享前准备 URI）转到 IO 线程。
 *
 * 事件由 [AppEventHub] 统一产生，每个页面的模块实例在 addEventListener 时注册、页面销毁时注销。
 * 除契约里的 connectivity / lifecycle / deepLink / keyboard 外，另有一个补充事件
 * `{event:"systemNav", threeButton, bottomInset}`：窗口 inset 就绪或用户切换导航方式时发给当前页，
 * 供需要的页面重读 getSystemNavMode（不关心的页面忽略即可）。
 */
class SDAppModule : SDCoreBaseModule() {

    private val listeners = CopyOnWriteArrayList<AppEventHub.Listener>()
    private var shareSession: ShareSession? = null
    private var activityResultHook: IKuiklyRenderLifecycleCallback? = null

    override fun handle(method: String, p: JSONObject, callback: KuiklyRenderCallback?): Any? {
        when (method) {
            // ---------------- 同步 ----------------
            "getAppInfo" -> return appInfoJson
            "getDeviceInfo" -> return deviceInfoJson
            "getInstallChannel" -> return InstallChannel.get(appContext)
            "getSystemNavMode" -> return systemNavMode()
            "getConnectivity" -> return JSONObject().put("types", AppEventHub.connectivityJson()).toString()
            "getPaths" -> return paths()
            "getInitialLink" -> return AppEventHub.takeInitialLink(this)
            "now" -> return System.currentTimeMillis().toString()
            "canOpenUrl" -> return if (canOpenUrl(p.optString("url"))) "1" else "0"

            // ---------------- 异步 ----------------
            "setStatusBarStyle" -> hostActivity?.setStatusBarStyle(p.optString("style", "light"))
            "setKeepScreenOn" -> ActivityStack.setKeepScreenOn(p.optBoolean("on", false))
            "setGestureExclusionRects" -> setGestureExclusionRects(p.optJSONArray("rects"))
            "copyToClipboard" -> copyToClipboard(p.optString("text"))
            "hideKeyboard" -> hideKeyboard()
            "haptic" -> haptic(p.optString("type", "light"))
            "openUrl" -> reply(callback, SDCoreKit.ok().put("opened", openUrl(p.optString("url"), p.optString("mode", "external"))))
            "openAppSettings" -> openAppSettings()
            "openNotificationSettings" -> openNotificationSettings()
            "openStoreListing" -> openStoreListing()
            "requestReview" -> requestReview(callback)
            "exitApp" -> exitApp()
            "share" -> share(p, callback)
            "addEventListener" -> addEventListener(p.optString("listenerId", ""), callback)
            "removeEventListener" -> removeEventListener(p.optString("listenerId", ""))
            else -> return unknown(method, callback)
        }
        return null
    }

    override fun onDestroy() {
        listeners.clear()
        AppEventHub.removeOwner(this)
        shareSession?.dispose()
        shareSession = null
        activityResultHook?.let { removeKuiklyRenderLifeCycleCallback(it) }
        activityResultHook = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 信息查询（同步）

    private fun systemNavMode(): String {
        val host = hostActivity ?: ActivityStack.top()
        return JSONObject()
            .put("threeButton", host?.threeButtonNav ?: (Build.VERSION.SDK_INT < 30))
            .put("bottomInset", host?.navBottomInsetDp ?: 0.0)
            .toString()
    }

    private fun paths(): String {
        val ctx = appContext
        val temp = File(ctx.cacheDir, "tmp")
        if (!temp.exists()) temp.mkdirs()
        return JSONObject()
            .put("tempDir", temp.absolutePath)
            .put("documentsDir", ctx.filesDir.absolutePath)
            .put("cacheDir", ctx.cacheDir.absolutePath)
            .toString()
    }

    private fun canOpenUrl(raw: String): Boolean {
        var url = raw.trim()
        if (url.isEmpty()) return false
        // 允许只传 scheme（"alipays"）
        if (!url.contains(':')) url = "$url://"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            @Suppress("DEPRECATION")
            appContext.packageManager.resolveActivity(intent, 0) != null
        } catch (e: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ 系统 UI

    private fun setGestureExclusionRects(rects: JSONArray?) {
        val host = hostActivity ?: return
        val list = ArrayList<Rect>()
        if (rects != null) {
            for (i in 0 until rects.length()) {
                val r = rects.optJSONObject(i) ?: continue
                val left = SDCoreKit.dpToPx(host, r.optDouble("x", 0.0))
                val top = SDCoreKit.dpToPx(host, r.optDouble("y", 0.0))
                val width = SDCoreKit.dpToPx(host, r.optDouble("w", 0.0))
                val height = SDCoreKit.dpToPx(host, r.optDouble("h", 0.0))
                if (width > 0 && height > 0) list.add(Rect(left, top, left + width, top + height))
            }
        }
        host.setGestureExclusionRects(list)
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("", text))
        } catch (e: Throwable) {
            // 个别 ROM 在失焦时写剪贴板会抛 SecurityException
            SDCoreKit.logE("copyToClipboard failed", e)
        }
    }

    private fun hideKeyboard() {
        val act = activity ?: return
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val focused = act.currentFocus
        val token = (focused ?: act.window.decorView).windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
        focused?.clearFocus()
    }

    /** 与 Flutter HapticFeedback.lightImpact / mediumImpact / heavyImpact / selectionClick 在 Android 上的映射一致。 */
    private fun haptic(type: String) {
        val view = activity?.window?.decorView ?: return
        val constant = when (type) {
            "medium" -> HapticFeedbackConstants.KEYBOARD_TAP
            "heavy" -> HapticFeedbackConstants.CONTEXT_CLICK
            "selection" -> HapticFeedbackConstants.CLOCK_TICK
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    private fun exitApp() {
        if (ActivityStack.isEmpty()) {
            activity?.finishAffinity()
        } else {
            ActivityStack.finishAll()
        }
    }

    // ------------------------------------------------------------------ 打开外部页面

    private fun openUrl(raw: String, mode: String): Boolean {
        val url = raw.trim()
        if (url.isEmpty()) return false
        val intent = try {
            if (url.startsWith("intent:")) {
                // intent:// 链接只允许拉起对外暴露的可浏览页面，丢弃显式组件，避免被用来打开任意内部 Activity
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        } catch (e: Throwable) {
            return false
        }
        val scheme = intent.data?.scheme?.lowercase()
        if (mode == "inApp" && (scheme == "http" || scheme == "https")) {
            // 应用内浏览器：Chrome Custom Tabs。这里手工带上协议约定的 extra（等价于 CustomTabsIntent.Builder 的产物），
            // 不为此引入 androidx.browser；默认浏览器不支持 Custom Tabs 时这些 extra 会被忽略，退化为普通的浏览器打开。
            val extras = Bundle()
            extras.putBinder(EXTRA_CUSTOM_TABS_SESSION, null)
            intent.putExtras(extras)
            intent.putExtra(EXTRA_CUSTOM_TABS_TOOLBAR_COLOR, Color.BLACK)
            intent.putExtra(EXTRA_CUSTOM_TABS_TITLE_VISIBILITY, 1)
        }
        return startExternal(intent)
    }

    private fun startExternal(intent: Intent): Boolean {
        return try {
            val act = activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                act.startActivity(intent)
            } else {
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Throwable) {
            SDCoreKit.logE("startActivity failed: ${intent.action}", e)
            false
        }
    }

    private fun openAppSettings(): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startExternal(intent)
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // 个别 ROM 没有单独的通知设置页：退到应用详情页
        if (!startExternal(intent)) openAppSettings()
    }

    /** 应用商店详情页：交给设备上的应用市场处理（华为 / 小米 / OPPO / vivo / 荣耀 / 应用宝都响应 market://）。 */
    private fun openStoreListing(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startExternal(intent)
    }

    /**
     * 应用内评分。
     *
     * 参考工程用的 in_app_review 在 Android 上是 Google Play In-App Review（com.google.android.play:review）。
     * 本工程是国内版，只在华为 / 小米 / OPPO / vivo / 荣耀 / 应用宝 / 抖音渠道分发，设备上没有 Play 服务，
     * 这个 API 永远不可用（参考工程里 isAvailable() 为 false，点了没有任何反应），还会平白带进 Play Core 依赖。
     * 国内各家应用市场也没有统一的应用内评分接口，所以这里不接 Play Review，
     * 改为跳到应用市场的详情页让用户去评分，并如实返回 shown=false（没有展示应用内评分面板）；
     * storeOpened 表示是否成功拉起了应用市场，调用方不需要再自己调 openStoreListing。
     */
    private fun requestReview(callback: KuiklyRenderCallback?) {
        val opened = openStoreListing()
        reply(callback, SDCoreKit.ok().put("shown", false).put("storeOpened", opened))
    }

    // ------------------------------------------------------------------ 分享

    private class ShareSession(
        val context: Context,
        val callback: KuiklyRenderCallback?,
        val onFinish: (ShareSession, String) -> Unit,
    ) {
        var receiver: BroadcastReceiver? = null
        var finished = false
        var pendingDismiss: Runnable? = null

        fun finish(status: String) {
            if (finished) return
            dispose()
            onFinish(this, status)
        }

        fun dispose() {
            finished = true
            pendingDismiss?.let { SDCoreKit.mainHandler.removeCallbacks(it) }
            pendingDismiss = null
            receiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (e: Throwable) {
                    // 已注销
                }
            }
            receiver = null
        }
    }

    private fun share(p: JSONObject, callback: KuiklyRenderCallback?) {
        val text = p.optString("text").takeIf { it.isNotEmpty() }
        val subject = p.optString("subject").takeIf { it.isNotEmpty() }
        val mimeType = p.optString("mimeType").takeIf { it.isNotEmpty() }
        val paths = ArrayList<String>()
        p.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) {
                val path = SDCoreKit.localPath(arr.optString(i))
                if (path.isNotEmpty()) paths.add(path)
            }
        }
        if (text == null && paths.isEmpty()) {
            reply(callback, SDCoreKit.fail("nothing to share").put("status", STATUS_UNAVAILABLE))
            return
        }
        if (paths.isEmpty()) {
            launchShare(buildShareIntent(text, subject, emptyList(), "text/plain"), callback)
            return
        }
        // 生成 content:// URI 可能要先把文件拷进缓存目录，放到 IO 线程
        SDCoreKit.io.execute {
            val prepared = try {
                val uris = prepareShareUris(paths)
                buildShareIntent(text, subject, uris, mimeType ?: guessMimeType(paths))
            } catch (e: Throwable) {
                reply(callback, SDCoreKit.fail(SDCoreKit.errorText(e)).put("status", STATUS_UNAVAILABLE))
                return@execute
            }
            SDCoreKit.runOnMain { launchShare(prepared, callback) }
        }
    }

    private fun prepareShareUris(paths: List<String>): List<Uri> {
        val ctx = appContext
        val shareRoot = File(ctx.cacheDir, "sd_share")
        // 清掉一天前的分享副本
        shareRoot.listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > 24 * 3600 * 1000L) it.deleteRecursively()
        }
        var copyDir: File? = null
        return paths.map { path ->
            val file = File(path)
            if (!file.isFile) throw FileNotFoundException("file not found: $path")
            try {
                SDCoreKit.contentUriFor(ctx, file)
            } catch (e: IllegalArgumentException) {
                // 不在 FileProvider 白名单目录里（如公共下载目录）：拷一份到缓存再授权
                val dir = copyDir ?: File(shareRoot, System.nanoTime().toString()).also {
                    it.mkdirs()
                    copyDir = it
                }
                val copy = File(dir, file.name)
                file.copyTo(copy, overwrite = true)
                SDCoreKit.contentUriFor(ctx, copy)
            }
        }
    }

    private fun guessMimeType(paths: List<String>): String {
        val types = paths.map { path ->
            val ext = path.substringAfterLast('.', "").lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }.distinct()
        if (types.size == 1) return types[0]
        val majors = types.map { it.substringBefore('/') }.distinct()
        return if (majors.size == 1) "${majors[0]}/*" else "*/*"
    }

    private fun buildShareIntent(text: String?, subject: String?, uris: List<Uri>, mimeType: String): Intent {
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
        intent.type = mimeType
        if (text != null) intent.putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        if (uris.isNotEmpty()) {
            if (uris.size == 1) {
                intent.putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            // ClipData + 授权标记：分享面板的预览与目标应用都能读到文件
            val clip = ClipData.newRawUri("", uris[0])
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    /**
     * 拉起系统分享面板并判定结果（做法同 share_plus）：
     * - 用户选了目标应用：系统通过 IntentSender 通知 → success；
     * - 面板关闭且没有收到通知 → dismissed；
     * - 无法拉起面板，或没有 Activity 可接收结果 → unavailable。
     */
    private fun launchShare(shareIntent: Intent, callback: KuiklyRenderCallback?) {
        if (destroyed) return
        val act = activity
        if (act == null || act.isFinishing || act.isDestroyed) {
            val chooser = Intent.createChooser(shareIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startExternal(chooser)
            reply(callback, SDCoreKit.ok().put("status", STATUS_UNAVAILABLE))
            return
        }
        // 同一页同时只跟踪一个分享面板
        shareSession?.finish(STATUS_DISMISSED)

        val ctx = appContext
        val session = ShareSession(ctx, callback) { s, status ->
            if (shareSession === s) shareSession = null
            reply(s.callback, SDCoreKit.ok().put("status", status))
        }
        val token = shareSeq.incrementAndGet()
        val action = "${ctx.packageName}.SD_SHARE_CHOSEN.$token"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                session.finish(STATUS_SUCCESS)
            }
        }
        val chooser = try {
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            session.receiver = receiver
            // 系统会往这个 PendingIntent 里补充 EXTRA_CHOSEN_COMPONENT，所以必须可变；限定包名后只有本应用能收到
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val sender = PendingIntent.getBroadcast(ctx, token and 0xFFFF, Intent(action).setPackage(ctx.packageName), flags)
            Intent.createChooser(shareIntent, null, sender.intentSender)
        } catch (e: Throwable) {
            SDCoreKit.logE("share: result tracking unavailable", e)
            Intent.createChooser(shareIntent, null)
        }

        ensureActivityResultHook()
        shareSession = session
        try {
            act.startActivityForResult(chooser, REQUEST_SHARE)
        } catch (e: Throwable) {
            SDCoreKit.logE("share: cannot open chooser", e)
            session.finish(STATUS_UNAVAILABLE)
        }
    }

    private fun ensureActivityResultHook() {
        if (activityResultHook != null) return
        val hook = object : IKuiklyRenderLifecycleCallback {
            override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
                if (requestCode != REQUEST_SHARE) return
                val session = shareSession ?: return
                if (session.finished) return
                // 面板已关闭。「已选择」的广播可能比这里晚一点到，稍等再判定为取消
                val dismiss = Runnable { session.finish(STATUS_DISMISSED) }
                session.pendingDismiss = dismiss
                SDCoreKit.mainHandler.postDelayed(dismiss, SHARE_DISMISS_GRACE_MS)
            }
        }
        activityResultHook = hook
        addKuiklyRenderLifecycleCallback(hook)
    }

    // ------------------------------------------------------------------ 事件

    private fun addEventListener(listenerId: String, callback: KuiklyRenderCallback?) {
        if (callback == null) return
        val host = hostActivity
        val listener = AppEventHub.Listener(host, this, listenerId) { event -> reply(callback, event) }
        listeners.add(listener)
        AppEventHub.addListener(listener)
        host?.startKeyboardWatching()
    }

    /** Kuikly 侧注销单个监听（根页面常驻，靠页面销毁释放不够）。 */
    private fun removeEventListener(listenerId: String) {
        if (listenerId.isEmpty()) return
        for (l in listeners) {
            if (l.listenerId == listenerId) {
                listeners.remove(l)
                AppEventHub.removeListener(l)
            }
        }
    }

    companion object {
        const val MODULE_NAME = "SDAppModule"

        private const val STATUS_SUCCESS = "success"
        private const val STATUS_DISMISSED = "dismissed"
        private const val STATUS_UNAVAILABLE = "unavailable"

        /** startActivityForResult 的请求码（FragmentActivity 要求 ≤ 0xFFFF）。 */
        private const val REQUEST_SHARE = 0x5D10
        private const val SHARE_DISMISS_GRACE_MS = 400L
        private val shareSeq = AtomicInteger(0)

        private const val EXTRA_CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
        private const val EXTRA_CUSTOM_TABS_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"
        private const val EXTRA_CUSTOM_TABS_TITLE_VISIBILITY = "android.support.customtabs.extra.TITLE_VISIBILITY"

        /** {appName, version, buildNumber, packageName}：进程内不变，算一次。 */
        private val appInfoJson: String by lazy {
            val ctx = SDCoreKit.app
            val appName = try {
                ctx.applicationInfo.loadLabel(ctx.packageManager).toString()
            } catch (e: Throwable) {
                ""
            }
            JSONObject()
                .put("appName", appName)
                .put("version", BuildConfig.VERSION_NAME)
                .put("buildNumber", BuildConfig.VERSION_CODE.toString())
                .put("packageName", ctx.packageName)
                .toString()
        }

        /**
         * 设备信息只取 android.os.Build 里的公开字段（与参考工程 device_info_plus 用到的一致），
         * 不读取任何设备标识；deviceName 用机型名，不读系统设置里用户自定义的设备名称。
         */
        private val deviceInfoJson: String by lazy {
            JSONObject()
                .put("platform", "android")
                .put("osVersion", Build.VERSION.RELEASE ?: "")
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER ?: "")
                .put("brand", Build.BRAND ?: "")
                .put("model", Build.MODEL ?: "")
                .put("hardware", Build.HARDWARE ?: "")
                .put("deviceName", Build.MODEL ?: "")
                .toString()
        }
    }
}
