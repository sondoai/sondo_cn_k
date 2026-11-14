package com.sondo.app.service

import com.sondo.app.base.AppEnv
import com.sondo.app.base.AppHost
import com.sondo.app.base.VisiblePage
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.HostBinding
import com.sondo.app.core.utils.Uuid
import com.sondo.app.core.utils.delayMs
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDHttpModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 商店版本查询结果。 */
class VersionStatus(val localVersion: String, val storeVersion: String, val appStoreLink: String) {
    /** 商店版本按语义化版本大于本地版本。 */
    val canUpdate: Boolean get() = AppUpdateService.compareVersion(storeVersion, localVersion) > 0
}

/**
 * 启动后的「应用火爆」提示与版本更新检查（对应 Flutter 侧 AppUpdateService）。
 *
 * 版本来源与参考工程一致：不走后端，直接查商店（iOS：iTunes lookup；Android：Google Play 详情页）。
 * 国内网络下 Google Play 不可达，Android 的更新弹窗实际不会出现（参考工程同样如此）；鸿蒙没有商店查询，跳过。
 * 是否弹、是否强更由 ConfigService.isCheckUpdate / isMandatoryUpdate 控制。
 */
object AppUpdateService {
    const val androidId = "com.sondo.music.video.ai"
    const val iOSId = "6751086438"

    private val hostBinding = HostBinding()

    fun init() {
        // 每个根页面生命周期只检查一次
        if (!hostBinding.needsBind()) return
        DevLog.logger("checkForUpdate-=-=-=")
        AppHost.launch { checkAppHotOpen() }
    }

    suspend fun checkAppHotOpen() {
        if (ConfigService.isAppHotOpen()) {
            // 画在用户正在看的页面上（首启时引导页会盖住根页面），之后被新页面盖住也会跟过去
            VisiblePage.showFollowing { ServiceDialogs.showAppHot(it) }
            return
        }
        checkForUpdate()
    }

    /** 检查更新。商店接口的失败全部吞掉，不弹任何提示。 */
    suspend fun checkForUpdate() {
        DevLog.logger("checkForUpdate=====")
        delayMs(1000)
        try {
            val status = getVersionStatus() ?: return
            DevLog.logger("checkForUpdate:最新版本: ${status.storeVersion}")
            DevLog.logger("checkForUpdate:本地版本: ${status.localVersion}")
            DevLog.logger(
                "checkForUpdate:${status.canUpdate} ${status.localVersion} ${ConfigService.isCheckUpdate} ${ConfigService.isMandatoryUpdate}",
            )
            if (status.canUpdate && ConfigService.isCheckUpdate == 1) {
                // 是否强制更新由服务端控制
                showUpdateDialog(status, force = ConfigService.isMandatoryUpdate == 1)
            }
        } catch (e: Throwable) {
            DevLog.logger("checkForUpdate$e")
        }
    }

    private fun showUpdateDialog(status: VersionStatus, force: Boolean) {
        VisiblePage.showFollowing { pager ->
            ServiceDialogs.showUpdate(pager, status.storeVersion, force) {
                try {
                    // 交给原生打开本机可用的应用商店详情页（国内安卓没有 Google Play）。
                    pager.acquireModule<SDAppModule>(SDAppModule.MODULE_NAME).openStoreListing()
                } catch (e: Throwable) {
                    DevLog.logger("updateApp error $e")
                }
            }
        }
    }

    private suspend fun httpGet(url: String): String? {
        val http: SDHttpModule = AppHost.module(SDHttpModule.MODULE_NAME)
        val res = http.request(Uuid.v4(), "GET", url, JSONObject(), null, 30_000)
        if (!res.optBoolean("ok", false) || res.optInt("status", 0) != 200) return null
        return res.optString("body", "")
    }

    private suspend fun getVersionStatus(): VersionStatus? {
        val local = AppEnv.appVersionName
        if (local.isEmpty()) return null
        return when {
            AppEnv.isIOS -> {
                val body = httpGet("https://itunes.apple.com/lookup?id=$iOSId") ?: return null
                val results = JSONObject(body).optJSONArray("results") ?: return null
                val first = results.optJSONObject(0) ?: return null
                val version = first.optString("version", "")
                if (version.isEmpty()) return null
                VersionStatus(local, version, first.optString("trackViewUrl", ""))
            }
            AppEnv.isAndroid -> {
                val link = "https://play.google.com/store/apps/details?id=$androidId&hl=en_US"
                val body = httpGet(link) ?: return null
                // 详情页内嵌数据里的版本号：[[["1.2.3"]]
                val m = Regex("\\[\\[\\[\"(\\d+\\.\\d+(\\.[a-z0-9]+)*)\"\\]\\]").find(body) ?: return null
                VersionStatus(local, m.groupValues[1], link)
            }
            else -> null
        }
    }

    /** 逐段比较数字版本号（忽略 `+build` / `-suffix`）。a > b 返回正数。 */
    fun compareVersion(a: String, b: String): Int {
        fun parts(v: String): List<Int> =
            v.substringBefore('+').substringBefore('-').split('.').map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
