package com.sondo.app.core.utils

import com.sondo.app.base.AppEnv
import com.sondo.app.base.toast
import com.sondo.app.core.constants.AppConstants
import com.sondo.app.core.i18n.tr
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.nativeModule

/** 反馈邮件（对应 Flutter 侧 EmailHelper）。 */
object EmailHelper {
    /**
     * 拉起邮件客户端，正文带上应用 / 设备信息。
     * @param userId 当前用户 id（未登录传 null，与参考工程一样输出 "null"）
     */
    suspend fun sendEmail(content: String? = null, userId: Long? = null) {
        try {
            val app: SDAppModule = nativeModule(SDAppModule.MODULE_NAME)
            val appInfo = app.getAppInfo()
            val appName = appInfo.optString("appName", AppConstants.appName)
            val appVersion = appInfo.optString("version", AppEnv.appVersionName)

            val device = app.getDeviceInfo()
            var systemVersion = "Unknown"
            var deviceName = "Unknown"
            when {
                AppEnv.isAndroid -> {
                    systemVersion = "Android ${device.optString("osVersion", "")} (SDK ${device.optInt("sdkInt", 0)})"
                    deviceName = "${device.optString("manufacturer", "")} ${device.optString("model", "")}"
                }
                AppEnv.isIOS -> {
                    systemVersion = "iOS ${device.optString("osVersion", "")}"
                    deviceName = device.optString("deviceName", "")
                }
                AppEnv.isOhos -> {
                    systemVersion = "HarmonyOS ${device.optString("osVersion", "")}"
                    deviceName = "${device.optString("manufacturer", "")} ${device.optString("model", "")}"
                }
            }

            val emailBody = "AppName: $appName\nUser ID: $userId\nAppVersion: $appVersion\n" +
                "SystemVersion: $systemVersion\nDeviceName: $deviceName\n $content"
            val query = "subject=${urlEncode("Support Request")}&body=${urlEncode(emailBody)}"
            val opened = app.openUrl("mailto:${AppConstants.email}?$query", "external")
            // 没有安装邮件客户端
            if (!opened) toast("no_email_client".tr)
        } catch (e: Throwable) {
            toast("no_email_client".tr)
        }
    }
}
