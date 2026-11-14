package com.sondo.app.core.constants

import com.sondo.app.base.AppEnv

/** 应用常量（与 Flutter 侧 AppConstants 对应）。 */
object AppConstants {
    const val appName = "Sondo"

    /** 与两端系统关联配置保持一致，业务深链和微信回调共用域名、按路径分别处理。 */
    const val deepLinkHost = "go.sondo.vip"
    const val deepLinkPath = "/link"

    // —— 国内三方（与 API 环境无关）—— //
    /** 微信开放平台移动应用 AppId */
    const val wechatAppId = "wx5400b44f9df9df99"
    /** iOS 微信授权回跳入口，末尾保留斜杠，并与微信开放平台配置保持一致。 */
    const val wechatUniversalLink = "https://$deepLinkHost/wechat/"
    /** 极光推送 / 认证共用 AppKey（同一极光应用） */
    const val jpushAppKey = "19c4451c32f15deb993e6c7a"
    const val jverifyAppKey = "19c4451c32f15deb993e6c7a"
    const val jpushChannel = "developer-default"
    /** 友盟统计 AppKey。当前平台 key 为空时不初始化，事件不会上报。 */
    const val umengAndroidAppKey = "6a7c4ab0934d206f58598570"
    const val umengIosAppKey = "6a7c4af3934d206f58598573"
    const val umengOhosAppKey = "6a7c4a02e87a8d65f0b3a8e6"

    private val envApiBaseUrl: String
        get() = when (AppEnv.apiEnv) {
            "local" -> "http://192.168.10.248:31004/app-api"
            "test" -> "https://sondo.vip/app-api"
            else -> "https://sondo.vip/app-api"
        }

    /** 测试入口覆盖的主接口地址；只放内存，持久化由调用方写 Sp。 */
    private var debugApiBaseUrl: String? = null

    /** 主接口地址：仅 TOKEN 开时测试覆盖优先，正式包只走 apiEnv。 */
    val apiBaseUrl: String
        get() {
            if (AppEnv.showTokenEditor) {
                val o = debugApiBaseUrl
                if (!o.isNullOrEmpty()) return o
            }
            return envApiBaseUrl
        }

    fun applyDebugApiBaseUrl(url: String?) {
        if (!AppEnv.showTokenEditor) return
        debugApiBaseUrl = if (url.isNullOrEmpty()) null else url
    }

    val apiOtherUrl: String
        get() = when (AppEnv.apiEnv) {
            "local", "test" -> "http://150.136.249.184:30200"
            else -> "https://order-pull.sondo.ai"
        }

    val wsBaseUrl: String
        get() = when (AppEnv.apiEnv) {
            "local" -> "ws://192.168.10.248:31004"
            "test" -> "wss://sondo.vip"
            else -> "wss://sondo.vip"
        }

    /** 1：文生图->图生视频  0：文生视频 */
    const val mvType = 1

    /** MV / Dance 是否允许分享。关则隐藏入口并拦截分享调用；音乐分享不受影响。 */
    const val enableMvDanceShare = false
    /** 作品是否允许发布。关则隐藏入口并拦截发布/取消发布；歌曲编辑仍走同一接口，不受影响。 */
    const val enableWorkPublish = false
    /**
     * 是否允许从主入口进入订阅页、积分购买页和推荐奖励。
     * 关则隐藏积分详情上的「更多」、我的页「推荐奖励」、首页登录后的邀请横幅，其余主入口点击直接返回。
     */
    const val enableMemberPageEntry = false
    /** MV 创作是否允许上传 2 位歌手。关则最多 1 位。 */
    const val enableMvDualSinger = true

    const val storageKey = "sondo"
    const val email = "support.sondo@tunesphere.net"

    /** Apple 回归周卡，不复用幸运周卡。 */
    const val recallWeeklyIos = "ios_sub_vip_plan_weekly_winback"
    /** Apple 超级周订阅套餐 ID */
    val superWeeklyPlan = listOf("ios_super_pro_weekly_260330", "ios_super_pro_weekly_discount_260422")

    const val defaultAvatar = "https://sondo-oss.sondo.vip/default_head.png"
    const val privacyUrl = "https://sondo-cn-tos.sondofiles.cn/legal/02-privacy-policy.html"
    const val termsUrl = "https://sondo-cn-tos.sondofiles.cn/legal/01-user-agreement.html"
    /** 同意前可打开的《AI 生成内容使用声明》。 */
    const val aiContentStatementUrl = "https://sondo-cn-tos.sondofiles.cn/legal/08-ai-generated-content-statement.html"
    /** 隐私政策版本。修改个人信息处理范围时递增，触发重新同意。 */
    const val privacyPolicyVersion = "1"
    /** 关闭下载水印时提交的声明版本。 */
    const val aigcStatementVersion = "1"

    const val telegramUrl = "https://t.me/+vGjpULGKl-cwNGRl"
    const val sondoBlogUrl = "https://www.sondo.ai/blog"
    const val unSubscribeIosUrl = "https://support.apple.com/en-us/HT202039"
    const val xUrl = "https://x.com/SondoAi"
    const val discordUrl = "https://discord.gg/hZ5VuqdpTD"
    const val youTuBeUrl = "https://www.youtube.com/channel/UCOI5VcgqQnE0-os6HjVXtzA"
}

/** 与 Flutter 侧 GlobalData 对应。 */
object GlobalData {
    const val needShowGuide = "need_show_guide"
    val emailReg = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
    val passwordReg = Regex("^.{6,12}$")

    /** 登录 / 支付请求体 RSA 公钥（X.509 SubjectPublicKeyInfo，Base64）。 */
    const val rsaKey =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCAba8eCYwog78FhIvDubFsBcXdLMGIASJgHvSk/7vLtIjmuVfCgDGOX0BWSFdyYtGboHuJpnCzJo/maATWdD9R5UlQ7SzLHISIWWq1tv+greKL8qxuShhvoGdkhT2apmidZOgAplyDU3SMiEnF9hopC9frnQOEi3q01gvGNVB/OQIDAQAB"

    var isPurchasedInGuide = false

    /** 存储从外部传入的参数（深链等） */
    val externalParams = HashMap<String, String>()

    /** 分享业务深链，由系统直接打开歌曲详情。 */
    fun shareUrl(musicId: Long): String =
        "https://${AppConstants.deepLinkHost}${AppConstants.deepLinkPath}?deep_link_value=song_detail&music_id=$musicId"

    /** 邀请链接直接携带邀请码。用户资料尚未返回时不生成空邀请码链接。 */
    fun referralShare(code: String): String {
        if (code.trim().isEmpty()) return ""
        return "https://${AppConstants.deepLinkHost}${AppConstants.deepLinkPath}?deep_link_value=register&s_code=${com.sondo.app.core.utils.urlEncode(code)}"
    }

    private fun urlEncodeLegacy(s: String): String {
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 128 || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }
}
