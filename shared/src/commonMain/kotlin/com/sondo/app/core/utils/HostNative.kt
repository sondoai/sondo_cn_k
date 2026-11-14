package com.sondo.app.core.utils

import com.sondo.app.base.AppHost
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDCryptoModule
import com.sondo.app.nativeapi.SDFileModule
import com.sondo.app.nativeapi.SDHttpModule
import com.sondo.app.nativeapi.SDMediaPickerModule
import com.sondo.app.nativeapi.SDMediaProcessModule
import com.sondo.app.nativeapi.SDVideoCacheModule
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.timer.setTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 宿主页面上的原生模块（与 App 同寿命）。
 * 下载 / 上传 / 媒体处理这类可能跨页面的长任务必须用这里的模块，回调才不会随业务页销毁而丢失。
 * 会弹系统界面的调用（选择器、另存为、分享、权限、写相册）不要用这里的模块，见 [onPage]。
 */
object HostNative {
    val http: SDHttpModule get() = AppHost.module(SDHttpModule.MODULE_NAME)
    val file: SDFileModule get() = AppHost.module(SDFileModule.MODULE_NAME)
    val app: SDAppModule get() = AppHost.module(SDAppModule.MODULE_NAME)
    val crypto: SDCryptoModule get() = AppHost.module(SDCryptoModule.MODULE_NAME)
    val mediaProcess: SDMediaProcessModule get() = AppHost.module(SDMediaProcessModule.MODULE_NAME)
    val mediaPicker: SDMediaPickerModule get() = AppHost.module(SDMediaPickerModule.MODULE_NAME)
    val videoCache: SDVideoCacheModule get() = AppHost.module(SDVideoCacheModule.MODULE_NAME)

    private var cachedTempDir = ""
    private var cachedDocumentsDir = ""

    /** 临时目录（不带结尾斜杠）。 */
    fun tempDir(): String {
        if (cachedTempDir.isEmpty()) loadPaths()
        return cachedTempDir
    }

    fun documentsDir(): String {
        if (cachedDocumentsDir.isEmpty()) loadPaths()
        return cachedDocumentsDir
    }

    private fun loadPaths() {
        val p = app.getPaths()
        cachedTempDir = p.optString("tempDir", "").trimEnd('/')
        cachedDocumentsDir = p.optString("documentsDir", "").trimEnd('/')
        if (cachedTempDir.isEmpty()) cachedTempDir = p.optString("cacheDir", "").trimEnd('/')
    }

    fun md5(text: String): String = crypto.md5(text)

    /** 宿主定时器上的挂起等待（不随业务页销毁而丢失）。 */
    suspend fun sleep(ms: Int) {
        val pagerId = AppHost.pager.pagerId
        suspendCoroutine<Unit> { cont -> setTimeout(pagerId, ms) { cont.resume(Unit) } }
    }

    /** 时间戳后缀：参考工程用微秒时间戳命名临时文件，这里用毫秒 + 自增序号保证唯一。 */
    private var seq = 0
    fun uniqueStamp(): String {
        seq = (seq + 1) % 1000
        return "${nowMs()}${seq.toString().padStart(3, '0')}"
    }
}

/**
 * 在 [pager] 的上下文里执行 [block]，结束后回到调用方的上下文继续。
 *
 * 弹系统界面的原生调用（文件 / 媒体选择器、另存为、分享面板、权限申请、写相册）必须由当前可见的页面发起：
 * Android 上每个页面是独立 Activity，activity-result 只投递给前台 Activity，挂在宿主（根）页面上发起的话
 * 要等用户回到首页才有结果。前后的长任务仍可在宿主上跑，只把这一步切到页面上；[block] 里用
 * `nativeModule(...)` / `currentPager()` 取到的就是 [pager]。
 * [pager] 中途销毁（回调随之丢失）时以 [onGone] 的值结束，调用方不会一直挂起。
 */
suspend fun <T> onPage(pager: Pager?, onGone: () -> T, block: suspend () -> T): T {
    val target = pager?.takeIf { isPagerAlive(it.pagerId) } ?: return block()
    val callerId = currentPagerId()
    if (callerId.isEmpty() || callerId == target.pagerId) return block()
    return suspendCoroutine { cont ->
        var done = false
        val finish: (Result<T>) -> Unit = { r ->
            if (!done) {
                done = true
                if (isPagerAlive(callerId)) setTimeout(callerId, 0) { cont.resumeWith(r) }
            }
        }
        setTimeout(target.pagerId, 0) {
            target.lifecycleScope.launch {
                val r = try {
                    Result.success(block())
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                finish(r)
            }
        }
        watchPageGone(target.pagerId, callerId, { done }) { finish(Result.success(onGone())) }
    }
}

/** 调用方页面上的看门定时器：[pagerId] 销毁后回调 [onGone]。 */
private fun watchPageGone(pagerId: String, timerOwner: String, isDone: () -> Boolean, onGone: () -> Unit) {
    if (!isPagerAlive(timerOwner)) return
    setTimeout(timerOwner, 500) {
        if (isDone()) return@setTimeout
        if (!isPagerAlive(pagerId)) onGone() else watchPageGone(pagerId, timerOwner, isDone, onGone)
    }
}

/**
 * 取消令牌（对应 Dio CancelToken / VideoMergerService.CancelToken）。
 * 取消时会中止登记在案的进行中 HTTP 请求。
 */
class CancelToken {
    var isCancelled: Boolean = false
        private set
    var reason: String = ""
        private set

    private val activeRequestIds = LinkedHashSet<String>()

    fun cancel(reason: String = "cancelled") {
        if (isCancelled) return
        isCancelled = true
        this.reason = reason
        val http = HostNative.http
        activeRequestIds.toList().forEach { http.cancel(it) }
        activeRequestIds.clear()
    }

    fun reset() {
        isCancelled = false
        reason = ""
    }

    internal fun track(requestId: String) {
        activeRequestIds.add(requestId)
    }

    internal fun untrack(requestId: String) {
        activeRequestIds.remove(requestId)
    }
}

/** 操作被取消。 */
class CancelledException(message: String = "cancelled") : Exception(message)
