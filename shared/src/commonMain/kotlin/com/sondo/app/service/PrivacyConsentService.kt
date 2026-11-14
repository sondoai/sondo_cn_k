package com.sondo.app.service

import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.constants.StorageKey
import com.sondo.app.core.storage.Sp
import com.sondo.app.core.utils.DateUtils
import com.sondo.app.core.utils.nowMs
import com.sondo.app.data.models.ModelDate

/**
 * 首次启动隐私同意的本地状态。
 *
 * 只访问本地键值存储，不依赖网络或任何第三方 SDK，可以在应用级服务启动之前使用。
 * 根页面的 Gate：`hasConsent()` 为 false 时只展示隐私同意界面；用户同意后 `grant()`，再调 AppBootstrap.start()。
 */
object PrivacyConsentService {
    /** 当前发布包使用的隐私政策版本。更新政策中的处理范围后递增此值。 */
    const val currentPolicyVersion: String = AppConstants.privacyPolicyVersion

    /** 当前政策版本是否已经获得同意。 */
    fun hasConsent(version: String = currentPolicyVersion): Boolean =
        Sp.has(StorageKey.privacyConsentVersion) && Sp.getString(StorageKey.privacyConsentVersion) == version

    /** 记录用户对指定政策版本的同意；[consentedAtMs] 缺省为当前时间，按 UTC ISO-8601 落盘。 */
    fun grant(version: String = currentPolicyVersion, consentedAtMs: Long? = null) {
        Sp.setString(StorageKey.privacyConsentVersion, version)
        Sp.setString(StorageKey.privacyConsentAt, DateUtils.utcIso8601(consentedAtMs ?: nowMs()))
    }

    /** 撤回当前同意。设置页的撤回入口可以调用它，下一次启动会再次展示 Gate。 */
    fun revoke() {
        Sp.remove(StorageKey.privacyConsentVersion)
        Sp.remove(StorageKey.privacyConsentAt)
    }

    /** 最近一次同意时间（epoch 毫秒）；旧版本或异常数据返回 null。 */
    val consentedAt: Long?
        get() {
            val value = Sp.getString(StorageKey.privacyConsentAt)
            if (value.isEmpty()) return null
            return ModelDate.parseIso(value)
        }

    // —— 与任务约定的别名 —— //
    fun accept() = grant()
    fun withdraw() = revoke()
}
