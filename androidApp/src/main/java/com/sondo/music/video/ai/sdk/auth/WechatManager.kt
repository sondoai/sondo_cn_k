package com.sondo.music.video.ai.sdk.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sondo.music.video.ai.sdk.SdkEventHub
import com.tencent.mm.opensdk.constants.ConstantsAPI
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory

/**
 * 微信登录（只用授权，没有支付和分享）。
 *
 * 流程：`wechatRegister` 注册 AppId → `wechatAuth` 拉起微信 → 微信回跳 `wxapi.WXEntryActivity` →
 * 这里把 `SendAuth.Resp` 作为 `wechatAuthResp` 事件广播给所有页面。state 校验、超时、换票都在 Kuikly 侧。
 * 隐私同意前不会创建 IWXAPI：只有用户点了微信登录才会调 `wechatRegister`。
 */
internal object WechatManager : IWXAPIEventHandler {

    private const val WECHAT_PACKAGE = "com.tencent.mm"

    val events = SdkEventHub()

    @Volatile
    private var api: IWXAPI? = null

    @Volatile
    private var appId: String = ""

    private var refreshReceiver: BroadcastReceiver? = null

    /** 重复注册同一个 AppId 直接复用。微信被清数据或重装后会发刷新广播，届时要重新 registerApp。 */
    @Synchronized
    fun register(context: Context, appId: String): Boolean {
        val id = appId.trim()
        if (id.isEmpty()) return false
        val current = api
        if (current != null && this.appId == id) return true
        return try {
            val app = context.applicationContext
            val created = WXAPIFactory.createWXAPI(app, id, true)
            val registered = created.registerApp(id)
            api = created
            this.appId = id
            if (refreshReceiver == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val currentId = this@WechatManager.appId
                        if (currentId.isNotEmpty()) api?.registerApp(currentId)
                    }
                }
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(ConstantsAPI.ACTION_REFRESH_WXAPP),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                refreshReceiver = receiver
            }
            registered
        } catch (_: Throwable) {
            false
        }
    }

    /** 注册前也能回答：直接查包名（清单 `<queries>` 已声明 com.tencent.mm）。 */
    fun isInstalled(context: Context?): Boolean {
        api?.let { wx ->
            try {
                return wx.isWXAppInstalled
            } catch (_: Throwable) {
            }
        }
        val pm = context?.packageManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(WECHAT_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(WECHAT_PACKAGE, 0)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 返回是否成功拉起微信；授权结果稍后经 [onResp] 事件到达。 */
    fun auth(scope: String, state: String): Boolean {
        val wx = api ?: return false
        return try {
            val req = SendAuth.Req()
            req.scope = scope.ifEmpty { "snsapi_userinfo" }
            req.state = state
            wx.sendReq(req)
        } catch (_: Throwable) {
            false
        }
    }

    /** WXEntryActivity 把回跳的 Intent 交进来。返回 false 表示这不是微信的回调或还没注册。 */
    fun handleIntent(intent: Intent?): Boolean {
        val wx = api ?: return false
        if (intent == null) return false
        return try {
            wx.handleIntent(intent, this)
        } catch (_: Throwable) {
            false
        }
    }

    override fun onReq(req: BaseReq?) = Unit

    override fun onResp(resp: BaseResp?) {
        if (resp !is SendAuth.Resp) return
        events.emit(
            "wechatAuthResp",
            "errCode" to resp.errCode,
            "code" to resp.code.orEmpty(),
            "state" to resp.state.orEmpty(),
        )
    }
}
