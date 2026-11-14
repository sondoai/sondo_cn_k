package com.sondo.app.service

import com.sondo.app.base.AppHost
import com.sondo.app.base.Routes
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DevLog
import com.sondo.app.core.utils.nowMs
import com.sondo.app.core.utils.parseQuery
import com.sondo.app.nativeapi.SDAppModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 系统深链入口（对应 Flutter 侧 analytics/deep_link.dart）。
 * 只处理系统交付的链接，首页就绪前缓存，不进行安装归因。
 *
 * 支持的链接：`sondo://…?…` 或 `https://go.sondo.vip/link?…`，查询参数：
 * - `deep_link_value=song_detail&music_id=<正整数>` → 歌曲详情
 * - `deep_link_value=register&s_code=<邀请码>` → 只把邀请码写入本地
 * - `deep_link_value=recall` → 回归落地页
 * - `deep_link_value=mv_activity&activity_code=<活动码>` → MV 活动页
 */
object DeepLinkRouter {
    private val supportedPages = setOf("song_detail", "register", "recall", "mv_activity")

    var homeReady = false
        private set

    private class PendingDeepLink(val page: String, val params: Map<String, String>)

    private var pending: PendingDeepLink? = null
    /** 已领取过冷启动链接的根页面；根页面在同一进程内重建后要重新领取。 */
    private var startedFor = ""
    private var initialLink = ""
    private var initialLinkAt = 0L

    /** 主框架首帧就绪后调用：允许并尝试消费 pending 深链。 */
    fun markHomeReady() {
        homeReady = true
        DevLog.logger("DeepLink-markHomeReady")
        tryConsumePending()
    }

    /**
     * 隐私同意后立即接收系统链接，不依赖友盟初始化或网络请求。
     * 运行期链接由 AppBootstrap 的 SDAppModule 事件监听转给 [ingestUrl]；这里只补读冷启动链接。
     */
    fun start() {
        if (!PrivacyConsentService.hasConsent()) return
        val hostId = AppHost.pager.pagerId
        if (startedFor == hostId) return
        startedFor = hostId
        try {
            val app: SDAppModule = AppHost.module(SDAppModule.MODULE_NAME)
            val link = app.getInitialLink()
            if (link.isNotEmpty()) {
                initialLink = link
                initialLinkAt = nowMs()
                ingestUrl(link, fromInitial = true)
            }
        } catch (e: Throwable) {
            DevLog.logger("DeepLink initial link error: $e")
        }
    }

    /** 只识别约定的 deep_link_value，各页面仅消费自己的业务参数。无效链接返回 null。 */
    fun parseDeepLinkQuery(query: Map<String, String>): Pair<String, Map<String, String>>? {
        val page = query["deep_link_value"] ?: ""
        if (!supportedPages.contains(page)) return null
        val params = LinkedHashMap(query)
        // 入队前校验目标，避免无效链接覆盖引导期间已经缓存的有效跳转。
        if (page == "song_detail") {
            val musicId = (params["music_id"] ?: "").toLongOrNull()
            if (musicId == null || musicId <= 0) return null
            params["music_id"] = "$musicId"
        } else if (page == "register" || page == "mv_activity") {
            val key = if (page == "register") "s_code" else "activity_code"
            val value = (params[key] ?: "").trim()
            if (value.isEmpty()) return null
            params[key] = value
        }
        return page to params
    }

    private fun isSupportedUrl(url: String): Boolean {
        val schemeEnd = url.indexOf(':')
        if (schemeEnd <= 0) return false
        val scheme = url.substring(0, schemeEnd).lowercase()
        var rest = url.substring(schemeEnd + 1)
        var authority = ""
        if (rest.startsWith("//")) {
            rest = rest.substring(2)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            authority = if (end < 0) rest else rest.substring(0, end)
            rest = if (end < 0) "" else rest.substring(end)
        }
        // 带 userinfo 的链接一律不认
        if (authority.contains('@')) return false
        if (scheme == "sondo") return true
        if (scheme != "https") return false
        val host = authority.substringBefore(':').lowercase()
        val port = authority.substringAfter(':', "")
        if (host != AppConstants.deepLinkHost) return false
        if (port.isNotEmpty() && port != "443") return false
        val path = rest.substringBefore('?').substringBefore('#')
        return path == AppConstants.deepLinkPath
    }

    /** 接收一条系统链接。返回是否是可识别的业务深链。 */
    fun ingestUrl(url: String, fromInitial: Boolean = false): Boolean {
        if (!PrivacyConsentService.hasConsent()) return false
        // 冷启动链接已经由 start() 读过；原生若在注册监听后又补发同一条，不重复处理。
        if (!fromInitial && url == initialLink && nowMs() - initialLinkAt < 5000) {
            initialLink = ""
            return false
        }
        if (!isSupportedUrl(url)) return false
        return try {
            val parsed = parseDeepLinkQuery(parseQuery(url)) ?: return false
            stash(parsed.first, parsed.second)
            true
        } catch (e: Throwable) {
            DevLog.logger("DeepLink invalid query: $e")
            false
        }
    }

    private fun stash(page: String, params: Map<String, String>) {
        // 每次外部点击都是新意图，不能用上次消费的签名阻止返回后再次打开。
        pending = PendingDeepLink(page, params)
        DevLog.logger("DeepLink-stash pending: $page homeReady=$homeReady")
        RecallFlow.deepLinkPending = page == "recall"
        tryConsumePending()
    }

    /** 仅首页就绪时消费；引导期间只保留 pending。 */
    private fun tryConsumePending() {
        val p = pending
        if (!homeReady || p == null) return
        pending = null
        DevLog.logger("DeepLink-consume pending: ${p.page}")
        when (p.page) {
            "song_detail" -> AppNav.push(Routes.musicDetail, JSONObject().put("id", p.params.getValue("music_id").toLong()))
            "register" -> {
                val code = p.params.getValue("s_code")
                Sp.setString(StorageKey.referralCode, code)
                DevLog.logger("DeepLink-consume register")
            }
            "recall" -> AppHost.launch { RecallFlow.openFromDeepLink() }
            "mv_activity" -> AppNav.push(
                Routes.mvActivity,
                JSONObject().put("activityCode", p.params.getValue("activity_code")),
            )
        }
    }
}
