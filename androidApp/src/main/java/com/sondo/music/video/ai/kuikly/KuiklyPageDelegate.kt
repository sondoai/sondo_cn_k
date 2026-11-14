package com.sondo.music.video.ai.kuikly

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.sondo.music.video.ai.BuildConfig
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.context.KuiklyRenderCoreExecuteModeBase
import com.tencent.kuikly.core.render.android.exception.ErrorReason
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegator
import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate

/**
 * 单个 Kuikly 页面的委托：注册原生扩展 Module / View，处理异常与加载回调。
 */
class KuiklyPageDelegate(
    private val activity: Activity,
    private val pageName: String,
    /** 页面首帧加载结束（无论成败）时在主线程回调一次。 */
    private val onLoadFinished: (() -> Unit)? = null,
) {

    fun createDelegator(): KuiklyRenderViewBaseDelegator {
        val delegate = object : KuiklyRenderViewBaseDelegatorDelegate {

            override fun registerExternalModule(kuiklyRenderExport: IKuiklyRenderExport) {
                super.registerExternalModule(kuiklyRenderExport)
                NativeRegistry.registerModules(kuiklyRenderExport)
            }

            override fun registerExternalRenderView(kuiklyRenderExport: IKuiklyRenderExport) {
                super.registerExternalRenderView(kuiklyRenderExport)
                NativeRegistry.registerViews(kuiklyRenderExport)
            }

            override fun coreExecuteModeX(): KuiklyRenderCoreExecuteModeBase {
                return KuiklyHotReload.executeMode()
            }

            override fun onUnhandledException(
                throwable: Throwable,
                errorReason: ErrorReason,
                executeMode: KuiklyRenderCoreExecuteModeBase
            ) {
                Log.e(TAG, "[$pageName] unhandled exception, reason=$errorReason", throwable)
                if (BuildConfig.DEBUG) {
                    androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("Kuikly 异常 [$pageName]")
                        .setMessage(throwable.stackTraceToString().take(4000))
                        .setPositiveButton("关闭") { d, _ -> d.dismiss() }
                        .show()
                }
            }

            override fun onPageLoadComplete(
                isSucceed: Boolean,
                errorReason: ErrorReason?,
                executeMode: KuiklyRenderCoreExecuteModeBase
            ) {
                if (!isSucceed) {
                    Log.e(TAG, "[$pageName] page load failed: $errorReason")
                }
                onLoadFinished?.let { activity.runOnUiThread(it) }
            }
        }
        return KuiklyRenderViewBaseDelegator(delegate)
    }

    fun onNewIntent(intent: Intent) {
        // 页面容器是 standard 模式，正常不会收到 onNewIntent。
        // 深链走 DeepLinkActivity → AppEventHub；推送点击由 SDPushModule 自己的入口处理。
    }

    companion object {
        private const val TAG = "KuiklyPage"
    }
}
