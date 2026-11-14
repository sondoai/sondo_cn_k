package com.sondo.app.core.utils

import com.sondo.app.base.BasePager
import com.sondo.app.base.currentPager
import com.sondo.app.core.i18n.tr
import com.sondo.app.core.i18n.trParams
import com.sondo.app.core.storage.Sp
import com.sondo.app.nativeapi.SDAppModule
import com.sondo.app.nativeapi.SDPermissionModule
import com.sondo.app.nativeapi.nativeModule

/**
 * 运行时权限（对应 Flutter 侧 PermissionUtil）。
 *
 * permission 取值见 [SDPermissionModule]："notification" | "camera" | "photos" | "photosAddOnly" |
 * "storage" | "microphone" | "tracking"。
 * 统一流程：系统授权框之前先弹应用内用途说明，用户点「同意」才继续调系统 API。
 */
object PermissionUtil {
    const val CAMERA = "camera"
    const val PHOTOS = "photos"
    const val PHOTOS_ADD_ONLY = "photosAddOnly"
    const val STORAGE = "storage"
    const val MICROPHONE = "microphone"
    const val NOTIFICATION = "notification"
    const val TRACKING = "tracking"

    private const val permissionPrefix = "permission_"

    private val module: SDPermissionModule get() = nativeModule(SDPermissionModule.MODULE_NAME)

    /** 检查权限状态 */
    suspend fun checkPermission(permission: String): String = module.status(permission)

    fun isUsable(status: String): Boolean = SDPermissionModule.isGranted(status)

    /**
     * 检查并请求权限。
     * @param purpose 当前业务场景的具体用途，传入后覆盖权限的默认说明
     */
    suspend fun checkAndRequestPermission(permission: String, purpose: String? = null, pager: BasePager? = null): Boolean {
        val status = module.status(permission)
        DevLog.logger("Initial permission status: $status")
        // 已授权
        if (isUsable(status)) return true
        // 首次请求或之前被拒绝（非永久拒绝），直接请求权限
        if (status == SDPermissionModule.DENIED || status == SDPermissionModule.NOT_DETERMINED) {
            val result = requestPermission(permission, purpose = purpose, pager = pager)
            DevLog.logger("Permission request result: $result")
            return result
        }
        // 永久拒绝 / 受限
        return false
    }

    /** 请求权限，并在系统授权框之前显示统一风格的用途说明弹窗。 */
    suspend fun requestPermission(
        permission: String,
        purpose: String? = null,
        rationaleAlreadyShown: Boolean = false,
        pager: BasePager? = null,
    ): Boolean = isUsable(requestPermissionStatus(permission, purpose, rationaleAlreadyShown, pager))

    /**
     * 请求权限并返回系统权限状态（需要区分 denied / permanentlyDenied 的调用方用这个）。
     * @param rationaleAlreadyShown 调用方已经展示了同一场景的前置说明时才传 true（如通知授权业务弹窗）
     */
    suspend fun requestPermissionStatus(
        permission: String,
        purpose: String? = null,
        rationaleAlreadyShown: Boolean = false,
        pager: BasePager? = null,
    ): String {
        val currentStatus = module.status(permission)
        DevLog.logger("Initial permission status: $currentStatus")
        if (isUsable(currentStatus) || currentStatus == SDPermissionModule.PERMANENTLY_DENIED ||
            currentStatus == SDPermissionModule.RESTRICTED
        ) {
            return currentStatus
        }

        if (!rationaleAlreadyShown) {
            val shouldRequest = showPermissionRationale(permission, purpose = purpose, pager = pager)
            if (!shouldRequest) {
                DevLog.logger("Permission rationale declined: $permission")
                return SDPermissionModule.DENIED
            }
        }

        return try {
            // 系统授权框由说明弹窗所在的页面发起
            val status = onPage(pager, { SDPermissionModule.DENIED }) { module.request(permission) }
            DevLog.logger("Permission request returned status: $status")
            savePermissionStatus(permission, isUsable(status))
            status
        } catch (e: Throwable) {
            DevLog.logger("Permission request error: $e")
            SDPermissionModule.DENIED
        }
    }

    /** 打开应用设置页面 */
    fun openSettings() {
        nativeModule<SDAppModule>(SDAppModule.MODULE_NAME).openAppSettings()
    }

    /**
     * 在会由选择器自己触发系统授权框的调用前，先展示用途说明。
     *
     * 这里仅负责说明，不主动申请额外权限（系统 Photo Picker 不会把 photos 变成已授权）。
     * 用户点过「同意」后把用途说明记到本地，下次直接打开选择器；本地 true 只表示已同意说明，
     * 不是系统已授权；永久拒绝仍以系统状态为准，走设置引导。
     */
    suspend fun confirmBeforeSystemPermission(permission: String, purpose: String? = null, pager: BasePager? = null): Boolean {
        val status = module.status(permission)
        if (isUsable(status)) return true
        if (status == SDPermissionModule.PERMANENTLY_DENIED) {
            val host = pager ?: currentPager() ?: return false
            ConfirmDialogLite.show(
                host,
                title = "permission_tips".tr,
                content = "permission_settings_required".trParams("name" to getPermissionName(permission)),
                cancelText = "permission_deny".tr,
                confirmText = "go_to_settings".tr,
                onConfirm = { openSettings() },
            )
            return false
        }
        if (status == SDPermissionModule.RESTRICTED) return false
        if (getSavedPermissionStatus(permission) == true) return true
        val agreed = showPermissionRationale(permission, purpose = purpose, pager = pager)
        // 拒绝不写入，下次仍弹说明。相册和相机使用不同的本地键。
        if (agreed) savePermissionStatus(permission, true)
        return agreed
    }

    private fun savePermissionStatus(permission: String, isGranted: Boolean) {
        Sp.setBool(permissionKey(permission), isGranted)
        DevLog.logger("Saved permission status: $permission = $isGranted")
    }

    /** 获取保存的权限状态；没有记录返回 null。 */
    fun getSavedPermissionStatus(permission: String): Boolean? {
        val key = permissionKey(permission)
        return if (Sp.has(key)) Sp.getBool(key) else null
    }

    private fun permissionKey(permission: String): String = "$permissionPrefix$permission"

    /** 获取权限显示名称 */
    fun getPermissionName(permission: String): String = when (permission) {
        CAMERA -> "permission_camera".tr
        PHOTOS, PHOTOS_ADD_ONLY -> "permission_photos".tr
        STORAGE -> "permission_storage".tr
        MICROPHONE -> "permission_microphone".tr
        NOTIFICATION -> "permission_notification".tr
        else -> "permission_unknown".tr
    }

    /**
     * 显示权限说明对话框，按钮固定为「拒绝」和「同意」。
     * 只有用户点击「同意」后，调用方才会继续触发系统授权框。
     */
    suspend fun showPermissionRationale(
        permission: String,
        title: String? = null,
        content: String? = null,
        purpose: String? = null,
        pager: BasePager? = null,
    ): Boolean {
        val host = pager ?: currentPager() ?: return false
        val permissionName = getPermissionName(permission)
        return ConfirmDialogLite.showChoice(
            host,
            title = title ?: "permission_required_title".trParams("name" to permissionName),
            content = content ?: rationaleContent(permission, permissionName, purpose),
            cancelText = "permission_deny".tr,
            confirmText = "permission_agree".tr,
        )
    }

    private fun rationaleContent(permission: String, permissionName: String, purpose: String?): String {
        val trimmedPurpose = purpose?.trim()
        if (!trimmedPurpose.isNullOrEmpty()) {
            // 模板在 @purpose 后已有句号；句末句号由模板补，避免拼出「。。」
            val purposeText = trimmedPurpose.replace(Regex("。+$"), "")
            return "permission_rationale_template".trParams("name" to permissionName, "purpose" to purposeText)
        }
        val key = when (permission) {
            CAMERA -> "permission_rationale_camera"
            PHOTOS -> "permission_rationale_photos"
            PHOTOS_ADD_ONLY -> "permission_rationale_photos_add_only"
            STORAGE -> "permission_rationale_storage"
            MICROPHONE -> "permission_rationale_microphone"
            NOTIFICATION -> "permission_rationale_notification"
            else -> "permission_rationale"
        }
        return key.trParams("name" to permissionName)
    }

    /**
     * iOS 首次联网会弹「无线数据」授权：先发一个请求触发系统框，再轮询连通性最多 40 秒。
     * 必须在隐私同意之后调用。
     */
    suspend fun requestNetworkPermission() {
        val app: SDAppModule = nativeModule(SDAppModule.MODULE_NAME)
        try {
            nativeModule<com.sondo.app.nativeapi.SDHttpModule>(com.sondo.app.nativeapi.SDHttpModule.MODULE_NAME)
                .probeContentLength(com.sondo.app.core.constants.AppConstants.defaultAvatar)
        } catch (e: Throwable) {
            // 只为触发系统授权框，结果无所谓
        }
        val deadline = nowMs() + 40_000
        while (nowMs() < deadline) {
            val types = app.getConnectivity().optJSONArray("types")
            if (types != null && types.length() > 0 && types.optString(0) != "none") {
                DevLog.logger("connectivity: has network")
                break
            }
            delayMs(500)
        }
    }
}
