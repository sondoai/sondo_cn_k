package com.sondo.app.nativeapi

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 应用 / 设备 / 系统 UI。
 *
 * 事件（[addEventListener]，每个页面各自注册）：
 * - {event:"connectivity", types:["wifi"|"mobile"|"ethernet"|"none"]}   广播给所有监听
 * - {event:"lifecycle", state:"resumed"|"paused"}            App 前后台；广播给所有监听
 * - {event:"deepLink", url}                                   sondo:// 或 https://go.sondo.vip/link…；投递规则见下
 * - {event:"keyboard", height}                                软键盘高度变化（dp / pt / vp）；只发给最顶层（拥有窗口 / 焦点）的页面
 *
 * 深链投递（三端一致，保证链接要么经 [getInitialLink] 取到、要么作为 deepLink 事件送达，绝不悄悄丢掉）：
 * - 原生侧暂存一个待投递链接（冷启动链接，或到达时还没有「深链消费者」的链接；新链接覆盖旧的）。
 * - 某个页面的模块调用 [getInitialLink] 即成为「深链消费者」：取走暂存链接（取后清空）。
 * - 此后到达的链接只投递给该消费者页面上、**在 getInitialLink 之后注册**的监听；
 *   这样的监听还没有时继续暂存，等它注册时补发给它。更早注册的监听（如 LaunchPage 的 systemNav 监听）永远收不到 deepLink。
 * - 消费者页面销毁后（同进程内根页面重建前）退回为向所有监听广播；下一次 getInitialLink 重新登记消费者。
 * 所以 Kotlin 侧的深链入口必须先调 [getInitialLink]、再注册监听（DeepLinkRouter.start → AppBootstrap.listenAppEvents），
 * 并且每个新的根页面都应调用一次 getInitialLink（同进程内根页面重建时也一样）。
 */
class SDAppModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    /** sync → {appName, version, buildNumber, packageName} */
    fun getAppInfo(): JSONObject = invokeSyncJson("getAppInfo")

    /** sync → {platform:"android"|"ios"|"ohos", osVersion, sdkInt, manufacturer, brand, model, hardware, deviceName} */
    fun getDeviceInfo(): JSONObject = invokeSyncJson("getDeviceInfo")

    /** sync → 渠道号（Android Walle；其余平台空串） */
    fun getInstallChannel(): String = invokeSync("getInstallChannel")

    /** sync → {threeButton: Boolean, bottomInset: Number}（Android 三键导航探测；其余平台 threeButton=false） */
    fun getSystemNavMode(): JSONObject = invokeSyncJson("getSystemNavMode")

    /** sync → {types:[…]} */
    fun getConnectivity(): JSONObject = invokeSyncJson("getConnectivity")

    fun isConnected(): Boolean {
        val types = getConnectivity().optJSONArray("types") ?: return true
        for (i in 0 until types.length()) if (types.optString(i) != "none") return true
        return types.length() == 0
    }

    /** sync → {tempDir, documentsDir, cacheDir} */
    fun getPaths(): JSONObject = invokeSyncJson("getPaths")

    /** sync → 暂存的深链 URL（没有则空串）。读取后原生侧清空，并把当前页面登记为深链消费者（见类注释）。 */
    fun getInitialLink(): String = invokeSync("getInitialLink")

    /** sync → 毫秒时间戳（System.currentTimeMillis） */
    fun now(): Long = invokeSync("now").toLongOrNull() ?: 0L

    /** style: "light"（浅色图标，深色背景用）| "dark" */
    fun setStatusBarStyle(style: String) = invoke("setStatusBarStyle", JSONObject().put("style", style))

    fun setKeepScreenOn(on: Boolean) = invoke("setKeepScreenOn", JSONObject().put("on", on))

    /** Android：排除系统手势区域（进度条拖动等）。rects: [{x,y,w,h}]（dp） */
    fun setGestureExclusionRects(rects: JSONArray) =
        invoke("setGestureExclusionRects", JSONObject().put("rects", rects))

    fun copyToClipboard(text: String) = invoke("copyToClipboard", JSONObject().put("text", text))

    fun hideKeyboard() = invoke("hideKeyboard")

    /** type: "light"|"medium"|"heavy"|"selection" */
    fun haptic(type: String = "light") = invoke("haptic", JSONObject().put("type", type))

    /**
     * 打开外部链接（https / mailto / alipays:// / 应用商店）。
     * mode: "external"（系统浏览器 / 目标 App）| "inApp"（应用内浏览器）
     * → {ok, opened}
     */
    suspend fun openUrl(url: String, mode: String = "external"): Boolean =
        await("openUrl", JSONObject().put("url", url).put("mode", mode)).optBoolean("opened", false)

    /** sync → 是否可处理该 scheme（如 "alipays://"、"weixin://"） */
    fun canOpenUrl(url: String): Boolean = invokeSync("canOpenUrl", JSONObject().put("url", url)) == "1"

    fun openAppSettings() = invoke("openAppSettings")

    /** 打开系统通知设置页 */
    fun openNotificationSettings() = invoke("openNotificationSettings")

    fun exitApp() = invoke("exitApp")

    /** 应用内评分（in_app_review）。→ {ok, shown} */
    suspend fun requestReview(): Boolean = await("requestReview").optBoolean("shown", false)

    /** 打开应用商店详情页 */
    fun openStoreListing() = invoke("openStoreListing")

    /**
     * 系统分享面板。
     * params: {text?, subject?, files?: [path], mimeType?}
     * → {ok, status: "success"|"dismissed"|"unavailable"}
     * - success 只在系统明确告知已分享到某个目标时返回；用户取消、或平台无法区分「完成 / 取消」时一律返回 dismissed
     *   （Kotlin 侧只在 success 时提示「分享成功」并累加转发数）。
     */
    suspend fun share(text: String? = null, subject: String? = null, files: List<String>? = null, mimeType: String? = null): JSONObject {
        val p = JSONObject()
        if (text != null) p.put("text", text)
        if (subject != null) p.put("subject", subject)
        if (files != null) p.put("files", JSONArray().also { a -> files.forEach { a.put(it) } })
        if (mimeType != null) p.put("mimeType", mimeType)
        return await("share", p)
    }

    private var nextListenerId = 0
    private val listenerIds = HashMap<CallbackRef, String>()

    /**
     * 注册事件监听（常驻）。params: {listenerId}（模块实例内唯一，供 [removeEventListener] 注销）。
     * 页面销毁时原生侧自动释放该页面的全部监听。
     */
    fun addEventListener(listener: (JSONObject) -> Unit): CallbackRef? {
        val id = "l${++nextListenerId}"
        val ref = listen("addEventListener", JSONObject().put("listenerId", id), listener)
        if (ref != null) listenerIds[ref] = id
        return ref
    }

    /** 注销单个监听：原生侧释放（params: {listenerId}），Kotlin 侧同时 removeCallback。页面常驻时（根页面）必须用它代替 removeCallback。 */
    fun removeEventListener(ref: CallbackRef) {
        val id = listenerIds.remove(ref)
        if (id != null) invoke("removeEventListener", JSONObject().put("listenerId", id))
        removeCallback(ref)
    }

    companion object {
        const val MODULE_NAME = "SDAppModule"
    }
}

/**
 * 运行时权限。
 * permission: "notification"|"camera"|"photos"|"photosAddOnly"|"storage"|"microphone"|"tracking"
 * status: "granted"|"limited"|"denied"|"permanentlyDenied"|"restricted"|"notDetermined"
 * - tracking = iOS ATT（其余平台恒 granted）；storage 仅 Android ≤ 32 有意义（更高版本恒 granted）。
 * - 说明弹窗（rationale）由 Kuikly 侧 UI 负责，原生只管查询与系统申请。
 */
class SDPermissionModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    suspend fun status(permission: String): String =
        await("status", JSONObject().put("permission", permission)).optString("status", "denied")

    suspend fun request(permission: String): String =
        await("request", JSONObject().put("permission", permission)).optString("status", "denied")

    companion object {
        const val MODULE_NAME = "SDPermissionModule"
        const val GRANTED = "granted"
        const val LIMITED = "limited"
        const val DENIED = "denied"
        const val PERMANENTLY_DENIED = "permanentlyDenied"
        const val RESTRICTED = "restricted"
        const val NOT_DETERMINED = "notDetermined"

        fun isGranted(status: String) = status == GRANTED || status == LIMITED
    }
}

/**
 * 文件操作（路径均为绝对路径）。全部异步，原生侧在 IO 线程执行。
 */
class SDFileModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    suspend fun exists(path: String): Boolean = await("exists", p(path)).optBoolean("exists", false)
    /** → 字节数；不存在为 -1 */
    suspend fun size(path: String): Long = await("size", p(path)).optLong("size", -1L)
    suspend fun delete(path: String): Boolean = await("delete", p(path)).ok()
    suspend fun copy(path: String, dest: String): Boolean = await("copy", p(path).put("dest", dest)).ok()
    suspend fun rename(path: String, dest: String): Boolean = await("rename", p(path).put("dest", dest)).ok()
    suspend fun readText(path: String): String? {
        val r = await("readText", p(path))
        return if (r.ok()) r.optString("text", "") else null
    }
    suspend fun writeText(path: String, text: String): Boolean = await("writeText", p(path).put("text", text)).ok()
    /** 读文件头 length 字节（魔数嗅探）→ base64 */
    suspend fun readHead(path: String, length: Int): String = await("readHead", p(path).put("length", length)).optString("base64", "")
    suspend fun md5File(path: String): String = await("md5File", p(path)).optString("md5", "")
    /** 把 shared assets（相对 common/ 的路径）拷到本地文件 */
    suspend fun copyAsset(asset: String, destPath: String): Boolean =
        await("copyAsset", JSONObject().put("asset", asset).put("destPath", destPath)).ok()
    /** → {ok, bytes} 缓存目录占用 */
    suspend fun cacheSize(): Long = await("cacheSize").optLong("bytes", 0L)
    /** 清空图片 / 视频 / 临时文件缓存 */
    suspend fun clearCache(): Boolean = await("clearCache").ok()
    /** 列目录 → {ok, files:[{name, path, size, isDir, modifiedMs}]} */
    suspend fun list(dir: String): JSONObject = await("list", p(dir))

    private fun p(path: String) = JSONObject().put("path", path)

    companion object {
        const val MODULE_NAME = "SDFileModule"
    }
}

/**
 * 加密。
 * - rsaEncrypt：RSA PKCS#1 v1.5，公钥为 X.509 SubjectPublicKeyInfo 的 Base64（可带 PEM 头尾，原生侧需自行剥离）→ Base64 密文
 * - hybridEncrypt：随机 AES-256 key + 16 字节 IV，AES/CBC/PKCS7 加密明文；key 用 RSA PKCS#1 v1.5 包裹
 *   → {data, key, iv}（全部 Base64；iv 为原始 IV 的 Base64）
 */
class SDCryptoModule : SDModule() {
    override fun moduleName(): String = MODULE_NAME

    fun rsaEncrypt(publicKey: String, text: String): String =
        invokeSync("rsaEncrypt", JSONObject().put("publicKey", publicKey).put("text", text))

    /** sync → {data, key, iv} */
    fun hybridEncrypt(publicKey: String, text: String): JSONObject =
        invokeSyncJson("hybridEncrypt", JSONObject().put("publicKey", publicKey).put("text", text))

    fun md5(text: String): String = invokeSync("md5", JSONObject().put("text", text))
    fun sha256(text: String): String = invokeSync("sha256", JSONObject().put("text", text))
    /** CSPRNG 十六进制串（length 为字符数） */
    fun randomHex(length: Int): String = invokeSync("randomHex", JSONObject().put("length", length))
    fun base64Encode(text: String): String = invokeSync("base64Encode", JSONObject().put("text", text))
    fun base64Decode(base64: String): String = invokeSync("base64Decode", JSONObject().put("base64", base64))

    companion object {
        const val MODULE_NAME = "SDCryptoModule"
    }
}
