package com.sondo.app.base

import com.tencent.kuikly.core.pager.PageData

/**
 * 运行环境（由宿主通过 pageData 注入，全进程一致）。
 * 对应 Flutter 侧 `--dart-define=API_ENV / TOKEN / LOGCAT`。
 */
object AppEnv {
    /** local | test | prod */
    var apiEnv: String = "prod"
        private set
    var isDebug: Boolean = false
        private set
    /** 设置页是否展示「修改 Token」测试入口 */
    var showTokenEditor: Boolean = false
        private set
    var appVersionName: String = ""
        private set
    var appVersionCode: Int = 0
        private set

    var isAndroid: Boolean = false
        private set
    var isIOS: Boolean = false
        private set
    var isOhos: Boolean = false
        private set
    var osVersion: String = ""
        private set

    private var inited = false

    fun initFrom(pageData: PageData) {
        if (inited) return
        inited = true
        val p = pageData.params
        apiEnv = p.optString("apiEnv", "prod").ifEmpty { "prod" }
        isDebug = p.optInt("debug", 0) == 1
        showTokenEditor = p.optInt("showTokenEditor", 0) == 1
        appVersionName = p.optString("appVersionName", pageData.appVersion)
        appVersionCode = p.optInt("appVersionCode", 0)
        isAndroid = pageData.isAndroid
        isIOS = pageData.isIOS
        isOhos = pageData.isOhOs
        osVersion = pageData.osVersion
    }

    /**
     * 后端 Request-Source：2 android / 3 ios。
     * 参考工程没有鸿蒙取值，暂按 2 上报，后端分配专用值后改这里。
     * 支付渠道不靠它区分：鸿蒙走独立的华为支付接口（ApiPaths.huawei*），见 docs/huawei-pay-api.md。
     */
    val requestSource: String
        get() = when {
            isIOS -> "3"
            else -> "2"
        }

    val platformName: String
        get() = when {
            isAndroid -> "android"
            isIOS -> "ios"
            isOhos -> "ohos"
            else -> "unknown"
        }
}
