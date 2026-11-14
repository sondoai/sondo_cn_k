package com.sondo.music.video.ai.sdk.pay

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.alipay.sdk.app.PayTask
import com.sondo.music.video.ai.sdk.OnceCallback
import com.sondo.music.video.ai.sdk.SdkSupport
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 支付宝 App 支付（积分包）。
 *
 * 原生只负责拉起收银台并把 `payV2` 的原始结果交回去；建单、轮询、待支付订单的持久化和回到前台后的补查
 * 都在 Kuikly 侧 PayService。`resultStatus` 为 9000 也不代表到账，只有后端订单状态算数。
 */
internal object AlipayManager {

    private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 同一时间只允许一笔：PayTask 会一直阻塞到收银台关闭，重复拉起只会叠两个收银台。 */
    private val paying = AtomicBoolean(false)

    /** 未安装时不建单。Android 11+ 依赖清单 `<queries>` 里声明的支付宝包名。 */
    fun isInstalled(context: Context?): Boolean {
        val pm = context?.packageManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(ALIPAY_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ALIPAY_PACKAGE, 0)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** orderStr 原样传给 SDK。payV2 是阻塞调用，必须离开主线程。 */
    fun pay(activity: Activity?, orderStr: String, callback: OnceCallback) {
        val payload = orderStr.trim()
        if (payload.isEmpty()) {
            callback.resolve(SdkSupport.fail("empty orderStr"))
            return
        }
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            callback.resolve(SdkSupport.fail("no activity"))
            return
        }
        if (!paying.compareAndSet(false, true)) {
            callback.resolve(SdkSupport.fail("busy"))
            return
        }
        thread(name = "alipay-pay") {
            val result = try {
                val raw = PayTask(activity).payV2(payload, true)
                SdkSupport.ok(
                    "resultStatus" to raw?.get("resultStatus").orEmpty(),
                    "result" to raw?.get("result").orEmpty(),
                    "memo" to raw?.get("memo").orEmpty(),
                )
            } catch (error: Throwable) {
                SdkSupport.fail(error.message ?: error.javaClass.simpleName)
            } finally {
                paying.set(false)
            }
            callback.resolve(result)
        }
    }
}
